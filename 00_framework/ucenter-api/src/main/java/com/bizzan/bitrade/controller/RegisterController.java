package com.bizzan.bitrade.controller;

import com.bizzan.bitrade.service.*;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.util.ByteSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.freemarker.FreeMarkerTemplateUtils;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.SessionAttribute;

import com.bizzan.bitrade.constant.CommonStatus;
import com.bizzan.bitrade.constant.MemberLevelEnum;
import com.bizzan.bitrade.constant.SysConstant;
import com.bizzan.bitrade.controller.sdk.NECaptchaVerifier;
import com.bizzan.bitrade.controller.sdk.NESecretPair;
import com.bizzan.bitrade.entity.*;
import com.bizzan.bitrade.entity.transform.AuthMember;
import com.bizzan.bitrade.event.MemberEvent;
import com.bizzan.bitrade.util.*;

import javax.annotation.Resource;
import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.bizzan.bitrade.constant.SysConstant.*;
import static com.bizzan.bitrade.util.MessageResult.error;
import static com.bizzan.bitrade.util.MessageResult.success;
import static org.springframework.util.Assert.isTrue;
import static org.springframework.util.Assert.notNull;

/**
 * ????????????
 *
 * @author GS
 * @date 2017???12???29???
 */
@Controller
@Slf4j
public class RegisterController {

    @Autowired
    private JavaMailSender javaMailSender;

    @Autowired
    private MemberLevelService memberLevelService;

    @Value("${spring.mail.username}")
    private String from;
    @Value("${spark.system.host}")
    private String host;
    @Value("${spark.system.name}")
    private String company;


    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private MemberService memberService;

    @Autowired
    private IdWorkByTwitter idWorkByTwitter;

    @Autowired
    private MemberEvent memberEvent;

    @Autowired
    private CountryService countryService;
    @Autowired
    private GeetestController gtestCon;

    @Resource
    private LocaleMessageSourceService localeMessageSourceService;

    @Autowired
    private RandomlyRegisteredService registeredService;

    private String userNameFormat = "U%06d";
    //??????????????????
    private static final String captchaId = "b7df23a75b054789b77c9d2cc7804fe9"; // ?????????id
    private static final String secretId = "8835fbc77225f5cf8dbc58613d78d2c4"; // ?????????id
    private static final String secretKey = "67d091cb32ac9efb387e83f1727e7fea"; // ?????????key

    private final NECaptchaVerifier verifier = new NECaptchaVerifier(captchaId, new NESecretPair(secretId, secretKey));


    /**
     * ?????????????????????
     *
     * @return
     */
    @RequestMapping(value = "/support/country", method = RequestMethod.POST)
    @ResponseBody
    public MessageResult allCountry() {
        MessageResult result = success();
        List<Country> list = countryService.getAllCountry();
        result.setData(list);
        return result;
    }

    /**
     * ???????????????????????????
     *
     * @param username
     * @return
     */
    @RequestMapping(value = "/register/check/username")
    @ResponseBody
    public MessageResult checkUsername(String username) {
        MessageResult result = success();
        if (memberService.usernameIsExist(username)) {
            result.setCode(500);
            result.setMessage(localeMessageSourceService.getMessage("ACTIVATION_FAILS_USERNAME"));
        }
        return result;
    }


    /**
     * ????????????  ????????????
     *
     * @param key
     * @param request
     * @return
     * @throws Exception
     */
//    @RequestMapping(value = "/register/active")
    @Transactional(rollbackFor = Exception.class)
    public String activate(String key, HttpServletRequest request) throws Exception {
        if (StringUtils.isEmpty(key)) {
            request.setAttribute("result", localeMessageSourceService.getMessage("INVALID_LINK"));
            return "registeredResult";
        }
        ValueOperations valueOperations = redisTemplate.opsForValue();
        Object info = valueOperations.get(key);
        LoginByEmail loginByEmail = null;
        if (info instanceof LoginByEmail) {
            loginByEmail = (LoginByEmail) info;
        }
        if (loginByEmail == null) {
            request.setAttribute("result", localeMessageSourceService.getMessage("INVALID_LINK"));
            return "registeredResult";
        }
        if (memberService.emailIsExist(loginByEmail.getEmail())) {
            request.setAttribute("result", localeMessageSourceService.getMessage("ACTIVATION_FAILS_EMAIL"));
            return "registeredResult";
        } else if (memberService.usernameIsExist(loginByEmail.getUsername())) {
            request.setAttribute("result", localeMessageSourceService.getMessage("ACTIVATION_FAILS_USERNAME"));
            return "registeredResult";
        }
        //??????redis???????????????
        valueOperations.getOperations().delete(key);
        valueOperations.getOperations().delete(loginByEmail.getEmail());
        //?????????????????????
        String loginNo = String.valueOf(idWorkByTwitter.nextId());
        //???
        String credentialsSalt = ByteSource.Util.bytes(loginNo).toHex();
        //????????????
        String password = Md5.md5Digest(loginByEmail.getPassword() + credentialsSalt).toLowerCase();
        Member member = new Member();
        //????????????????????? ??????0  ?????? ??????????????????   1 ??????????????? ....
        if (!StringUtils.isEmpty(loginByEmail.getSuperPartner())) {
            member.setSuperPartner(loginByEmail.getSuperPartner());
            if (!"0".equals(loginByEmail.getSuperPartner())) {
                //????????????????????????
                member.setStatus(CommonStatus.ILLEGAL);
            }

        }
        member.setMemberLevel(MemberLevelEnum.GENERAL);
        member.setLevel(memberLevelService.findDefault());
        Location location = new Location();
        location.setCountry(loginByEmail.getCountry());
        member.setLocation(location);
        Country country = new Country();
        country.setZhName(loginByEmail.getCountry());
        member.setCountry(country);
        member.setUsername(loginByEmail.getUsername());
        member.setPassword(password);
        member.setEmail(loginByEmail.getEmail());
        member.setSalt(credentialsSalt);
        Member member1 = memberService.save(member);
        if (member1 != null) {
            member1.setPromotionCode(GeneratorUtil.getPromotionCode(member1.getId()));
            memberEvent.onRegisterSuccess(member1, loginByEmail.getPromotion());
            request.setAttribute("result", localeMessageSourceService.getMessage("ACTIVATION_SUCCESSFUL"));
        } else {
            request.setAttribute("result", localeMessageSourceService.getMessage("ACTIVATION_FAILS"));
        }
        return "registeredResult";
    }

    /**
     * ???????????? ????????????
     *
     * @param loginByEmail
     * @param bindingResult
     * @return
     */
//    @RequestMapping("/register/email")
    @ResponseBody
    public MessageResult registerByEmail(@Valid LoginByEmail loginByEmail, BindingResult bindingResult) {
        MessageResult result = BindingResultUtil.validate(bindingResult);
        if (result != null) {
            return result;
        }
        String email = loginByEmail.getEmail();
        isTrue(!memberService.emailIsExist(email), localeMessageSourceService.getMessage("EMAIL_ALREADY_BOUND"));
        isTrue(!memberService.usernameIsExist(loginByEmail.getUsername()), localeMessageSourceService.getMessage("USERNAME_ALREADY_EXISTS"));
        isTrue(memberService.userPromotionCodeIsExist(loginByEmail.getPromotion()), localeMessageSourceService.getMessage("USER_PROMOTION_CODE_EXISTS"));
        ValueOperations valueOperations = redisTemplate.opsForValue();
        if (valueOperations.get(email) != null) {
            return error(localeMessageSourceService.getMessage("LOGIN_EMAIL_ALREADY_SEND"));
        }
        try {
            log.info("send==================================");
            sentEmail(valueOperations, loginByEmail, email);
            log.info("success===============================");
        } catch (Exception e) {
            e.printStackTrace();
            return error(localeMessageSourceService.getMessage("REGISTRATION_FAILED"));
        }
        return success(localeMessageSourceService.getMessage("SEND_LOGIN_EMAIL_SUCCESS"));
    }

    @Async
    public void sentEmail(ValueOperations valueOperations, LoginByEmail loginByEmail, String email) throws MessagingException, IOException, TemplateException {
        //???????????????????????????
        String token = UUID.randomUUID().toString().replace("-", "");
        MimeMessage mimeMessage = javaMailSender.createMimeMessage();
        MimeMessageHelper helper = null;
        helper = new MimeMessageHelper(mimeMessage, true);
        helper.setFrom(from);
        helper.setTo(email);
        helper.setSubject(company);
        Map<String, Object> model = new HashMap<>(16);
        model.put("username", loginByEmail.getUsername());
        model.put("token", token);
        model.put("host", host);
        model.put("name", company);
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_26);
        cfg.setClassForTemplateLoading(this.getClass(), "/templates");
        Template template = cfg.getTemplate("activateEmail.ftl");
        String html = FreeMarkerTemplateUtils.processTemplateIntoString(template, model);
        helper.setText(html, true);
        //????????????
        javaMailSender.send(mimeMessage);
        valueOperations.set(token, loginByEmail, 12, TimeUnit.HOURS);
        valueOperations.set(email, "", 12, TimeUnit.HOURS);
    }

    /**
     * ????????????
     *
     * @param loginByPhone
     * @param bindingResult
     * @return
     * @throws Exception
     */
    @RequestMapping("/register/phone")
    @ResponseBody
    @Transactional(rollbackFor = Exception.class)
    public MessageResult loginByPhone(
            @Valid LoginByPhone loginByPhone,
            BindingResult bindingResult, HttpServletRequest request) throws Exception {
        MessageResult result = BindingResultUtil.validate(bindingResult);
        if (result != null) {
            return result;
        }

        if ("??????".equals(loginByPhone.getCountry())) {
            Assert.isTrue(ValidateUtil.isMobilePhone(loginByPhone.getPhone().trim()), localeMessageSourceService.getMessage("PHONE_EMPTY_OR_INCORRECT"));
        }
        String ip = request.getHeader("X-Real-IP");
        String phone = loginByPhone.getPhone();
        ValueOperations valueOperations = redisTemplate.opsForValue();
        Object code = valueOperations.get(SysConstant.PHONE_REG_CODE_PREFIX + phone);
        isTrue(!memberService.phoneIsExist(phone), localeMessageSourceService.getMessage("PHONE_ALREADY_EXISTS"));
        isTrue(!memberService.usernameIsExist(loginByPhone.getUsername()), localeMessageSourceService.getMessage("USERNAME_ALREADY_EXISTS"));
        if (StringUtils.hasText(loginByPhone.getPromotion().trim())) {
            isTrue(memberService.userPromotionCodeIsExist(loginByPhone.getPromotion()), localeMessageSourceService.getMessage("USER_PROMOTION_CODE_EXISTS"));
        }
        //??????????????????????????? ????????????
//        isTrue(verifier.verify(loginByPhone.getValidate(),""),localeMessageSourceService.getMessage("VERIFICATION_CODE_INCORRECT"));
        //????????????????????????
//        isTrue(gtestCon.watherProof(loginByPhone.getTicket(),loginByPhone.getRandStr(),ip),localeMessageSourceService.getMessage("VERIFICATION_PICTURE_NOT_CORRECT"));
        // ??????????????????
        notNull(code, localeMessageSourceService.getMessage("VERIFICATION_CODE_NOT_EXISTS"));
        if (!code.toString().equals(loginByPhone.getCode())) {
            return error(localeMessageSourceService.getMessage("VERIFICATION_CODE_INCORRECT"));
        } else {
            valueOperations.getOperations().delete(SysConstant.PHONE_REG_CODE_PREFIX + phone);
        }
        //?????????????????????
        String loginNo = String.valueOf(idWorkByTwitter.nextId());
        //???
        String credentialsSalt = ByteSource.Util.bytes(loginNo).toHex();
        //????????????
        String password = Md5.md5Digest(loginByPhone.getPassword() + credentialsSalt).toLowerCase();
        Member member = new Member();
        //?????????????????????????????????

        MemberLevel memberLevel = memberLevelService.findDefault();
        String name = memberLevel.getName();
        String tel = phone;
        registeredService.addNewRegistered(name, tel);

        //????????????????????? ??????0  ?????? ??????????????????   1 ??????????????? >>2.?????????????????????
        if (!StringUtils.isEmpty(loginByPhone.getSuperPartner())) {
            member.setSuperPartner(loginByPhone.getSuperPartner());
            if (!"0".equals(loginByPhone.getSuperPartner())) {
                //????????????????????????
                member.setStatus(CommonStatus.ILLEGAL);
            }
        }
        member.setMemberLevel(MemberLevelEnum.GENERAL);
        MemberLevel lDefault = memberLevelService.findDefault();
        if (lDefault == null) {
            return error("???????????????????????????????????????");
        }
        member.setLevel(lDefault);
        Location location = new Location();
        location.setCountry(loginByPhone.getCountry());
        Country country = new Country();
        country.setZhName(loginByPhone.getCountry());
        member.setCountry(country);
        member.setLocation(location);
        member.setUsername(loginByPhone.getUsername());
        member.setPassword(password);
        member.setMobilePhone(phone);
        member.setSalt(credentialsSalt);
        member.setAvatar("https://bizzan.oss-cn-hangzhou.aliyuncs.com/defaultavatar.png"); // ??????????????????
        Member member1 = memberService.save(member);
        if (member1 != null) {
            // Member???@entity??????????????????????????????????????????????????????setPromotionCode???????????????????????????
            member1.setPromotionCode(GeneratorUtil.getPromotionCode(member1.getId()));
            memberEvent.onRegisterSuccess(member1, loginByPhone.getPromotion().trim());
            return success(localeMessageSourceService.getMessage("REGISTRATION_SUCCESS"));
        } else {
            return error(localeMessageSourceService.getMessage("REGISTRATION_FAILED"));
        }

    }


    /**
     * ????????????????????????
     * @param loginByPhone
     * @param bindingResult
     * @param request
     * @return
     * @throws Exception
     */
//    @RequestMapping("/register/for_phone")
//    @ResponseBody
//    @Transactional(rollbackFor = Exception.class)
//    public MessageResult loginByPhone4Mobiles(
//            @Valid LoginByPhone loginByPhone,
//            BindingResult bindingResult,HttpServletRequest request) throws Exception {
//        log.info("============??????PC?????????---registerPC");
//        return error(localeMessageSourceService.getMessage("REGISTER_TO_PC"));
//    }

    /**
     * ??????????????????
     *
     * @param loginByPhone
     * @param bindingResult
     * @param request
     * @return
     * @throws Exception
     */
    @RequestMapping("/register/for_phone")
    @ResponseBody
    @Transactional(rollbackFor = Exception.class)
    public MessageResult loginByPhone4Mobile(
            @Valid LoginByPhone loginByPhone,
            BindingResult bindingResult, HttpServletRequest request) throws Exception {
        MessageResult result = BindingResultUtil.validate(bindingResult);
        if (result != null) {
            return result;
        }

        if (loginByPhone.getCountry().equals("??????")) {
            Assert.isTrue(ValidateUtil.isMobilePhone(loginByPhone.getPhone().trim()), localeMessageSourceService.getMessage("PHONE_EMPTY_OR_INCORRECT"));
        }
        String ip = request.getHeader("X-Real-IP");
        String phone = loginByPhone.getPhone();
        ValueOperations valueOperations = redisTemplate.opsForValue();
        Object code = valueOperations.get(SysConstant.PHONE_REG_CODE_PREFIX + phone);
        isTrue(!memberService.phoneIsExist(phone), localeMessageSourceService.getMessage("PHONE_ALREADY_EXISTS"));
        isTrue(!memberService.usernameIsExist(loginByPhone.getUsername()), localeMessageSourceService.getMessage("USERNAME_ALREADY_EXISTS"));
        if (StringUtils.hasText(loginByPhone.getPromotion().trim())) {
            isTrue(memberService.userPromotionCodeIsExist(loginByPhone.getPromotion()), localeMessageSourceService.getMessage("USER_PROMOTION_CODE_EXISTS"));
        }
//        isTrue(memberService.userPromotionCodeIsExist(loginByPhone.getPromotion()),localeMessageSourceService.getMessage("USER_PROMOTION_CODE_EXISTS"));
        //??????????????????????????? ????????????
        //isTrue(verifier.verify(loginByPhone.getValidate(),""),localeMessageSourceService.getMessage("VERIFICATION_CODE_INCORRECT"));

        // ??????????????????
        notNull(code, localeMessageSourceService.getMessage("VERIFICATION_CODE_NOT_EXISTS"));
        if (!code.toString().equals(loginByPhone.getCode())) {
            return error(localeMessageSourceService.getMessage("VERIFICATION_CODE_INCORRECT"));
        } else {
            valueOperations.getOperations().delete(SysConstant.PHONE_REG_CODE_PREFIX + phone);
        }
        //?????????????????????
        String loginNo = String.valueOf(idWorkByTwitter.nextId());
        //???
        String credentialsSalt = ByteSource.Util.bytes(loginNo).toHex();
        //????????????
        String password = Md5.md5Digest(loginByPhone.getPassword() + credentialsSalt).toLowerCase();
        Member member = new Member();
        //????????????????????? ??????0  ?????? ??????????????????   1 ??????????????? >>2.?????????????????????
        if (!StringUtils.isEmpty(loginByPhone.getSuperPartner())) {
            member.setSuperPartner(loginByPhone.getSuperPartner());
            if (!"0".equals(loginByPhone.getSuperPartner())) {
                //????????????????????????
                member.setStatus(CommonStatus.ILLEGAL);
            }
        }
        member.setMemberLevel(MemberLevelEnum.GENERAL);
        member.setLevel(memberLevelService.findDefault());
        Location location = new Location();
        location.setCountry(loginByPhone.getCountry());
        Country country = new Country();
        country.setZhName(loginByPhone.getCountry());
        member.setCountry(country);
        member.setLocation(location);
        member.setUsername(loginByPhone.getUsername());
        member.setPassword(password);
        member.setMobilePhone(phone);
        member.setSalt(credentialsSalt);
        Member member1 = memberService.save(member);
        if (member1 != null) {
            member1.setPromotionCode(GeneratorUtil.getPromotionCode(member1.getId()));
            // ??????????????????
            memberEvent.onRegisterSuccess(member1, loginByPhone.getPromotion());
            return success(localeMessageSourceService.getMessage("REGISTRATION_SUCCESS"));
        } else {
            return error(localeMessageSourceService.getMessage("REGISTRATION_FAILED"));
        }
    }

    /**
     * ???????????????????????????
     *
     * @param email
     * @param user
     * @return
     */
    @RequestMapping("/bind/email/code")
    @ResponseBody
    @Transactional(rollbackFor = Exception.class)
    public MessageResult sendBindEmail(String email, @SessionAttribute(SESSION_MEMBER) AuthMember user) {
        Assert.isTrue(ValidateUtil.isEmail(email), localeMessageSourceService.getMessage("WRONG_EMAIL"));
        Member member = memberService.findOne(user.getId());
        Assert.isNull(member.getEmail(), localeMessageSourceService.getMessage("BIND_EMAIL_REPEAT"));
        Assert.isTrue(!memberService.emailIsExist(email), localeMessageSourceService.getMessage("EMAIL_ALREADY_BOUND"));
        String code = String.valueOf(GeneratorUtil.getRandomNumber(100000, 999999));
        ValueOperations valueOperations = redisTemplate.opsForValue();
        if (valueOperations.get(EMAIL_BIND_CODE_PREFIX + email) != null) {
            return error(localeMessageSourceService.getMessage("EMAIL_ALREADY_SEND"));
        }
        try {
            sentEmailCode(valueOperations, email, code);
        } catch (Exception e) {
            e.printStackTrace();
            return error(localeMessageSourceService.getMessage("SEND_FAILED"));
        }
        return success(localeMessageSourceService.getMessage("SENT_SUCCESS_TEN"));
    }

    @Async
    public void sentEmailCode(ValueOperations valueOperations, String email, String code) throws MessagingException, IOException, TemplateException {
        MimeMessage mimeMessage = javaMailSender.createMimeMessage();
        MimeMessageHelper helper = null;
        helper = new MimeMessageHelper(mimeMessage, true);
        helper.setFrom(from);
        helper.setTo(email);
        helper.setSubject(company);
        Map<String, Object> model = new HashMap<>(16);
        model.put("code", code);
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_26);
        cfg.setClassForTemplateLoading(this.getClass(), "/templates");
        Template template = cfg.getTemplate("bindCodeEmail.ftl");
        String html = FreeMarkerTemplateUtils.processTemplateIntoString(template, model);
        helper.setText(html, true);

        //????????????
        javaMailSender.send(mimeMessage);
        log.info("send email for {},content:{}", email, html);
        valueOperations.set(EMAIL_BIND_CODE_PREFIX + email, code, 10, TimeUnit.MINUTES);
    }

    /**
     * ???????????????????????????
     *
     * @param user
     * @return
     */
    @RequestMapping("/add/address/code")
    @ResponseBody
    @Transactional(rollbackFor = Exception.class)
    public MessageResult sendAddAddress(@SessionAttribute(SESSION_MEMBER) AuthMember user) {
        String code = String.valueOf(GeneratorUtil.getRandomNumber(100000, 999999));
        ValueOperations valueOperations = redisTemplate.opsForValue();
        Member member = memberService.findOne(user.getId());
        String email = member.getEmail();
        if (email == null) {
            return error(localeMessageSourceService.getMessage("NOT_BIND_EMAIL"));
        }
        if (valueOperations.get(ADD_ADDRESS_CODE_PREFIX + email) != null) {
            return error(localeMessageSourceService.getMessage("EMAIL_ALREADY_SEND"));
        }
        try {
            sentEmailAddCode(valueOperations, email, code);
        } catch (Exception e) {
            e.printStackTrace();
            return error(localeMessageSourceService.getMessage("SEND_FAILED"));
        }
        return success(localeMessageSourceService.getMessage("SENT_SUCCESS_TEN"));
    }

    @Async
    public void sentEmailAddCode(ValueOperations valueOperations, String email, String code) throws MessagingException, IOException, TemplateException {
        MimeMessage mimeMessage = javaMailSender.createMimeMessage();
        MimeMessageHelper helper = null;
        helper = new MimeMessageHelper(mimeMessage, true);
        helper.setFrom(from);
        helper.setTo(email);
        helper.setSubject(company);
        Map<String, Object> model = new HashMap<>(16);
        model.put("code", code);
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_26);
        cfg.setClassForTemplateLoading(this.getClass(), "/templates");
        Template template = cfg.getTemplate("addAddressCodeEmail.ftl");
        String html = FreeMarkerTemplateUtils.processTemplateIntoString(template, model);
        helper.setText(html, true);
        //????????????
        javaMailSender.send(mimeMessage);
        valueOperations.set(ADD_ADDRESS_CODE_PREFIX + email, code, 10, TimeUnit.MINUTES);
    }

    @RequestMapping("/reset/email/code")
    @ResponseBody
    @Transactional(rollbackFor = Exception.class)
    public MessageResult sendResetPasswordCode(String account) {
        Member member = memberService.findByEmail(account);
        Assert.notNull(member, localeMessageSourceService.getMessage("MEMBER_NOT_EXISTS"));
        ValueOperations valueOperations = redisTemplate.opsForValue();
        if (valueOperations.get(RESET_PASSWORD_CODE_PREFIX + account) != null) {
            return error(localeMessageSourceService.getMessage("EMAIL_ALREADY_SEND"));
        }
        try {
            String code = String.valueOf(GeneratorUtil.getRandomNumber(100000, 999999));
            sentResetPassword(valueOperations, account, code);
        } catch (Exception e) {
            e.printStackTrace();
            return error(localeMessageSourceService.getMessage("SEND_FAILED"));
        }
        return success(localeMessageSourceService.getMessage("SENT_SUCCESS_TEN"));
    }

    @Async
    public void sentResetPassword(ValueOperations valueOperations, String email, String code) throws MessagingException, IOException, TemplateException {
        MimeMessage mimeMessage = javaMailSender.createMimeMessage();
        MimeMessageHelper helper = null;
        helper = new MimeMessageHelper(mimeMessage, true);
        helper.setFrom(from);
        helper.setTo(email);
        helper.setSubject(company);
        Map<String, Object> model = new HashMap<>(16);
        model.put("code", code);
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_26);
        cfg.setClassForTemplateLoading(this.getClass(), "/templates");
        Template template = cfg.getTemplate("resetPasswordCodeEmail.ftl");
        String html = FreeMarkerTemplateUtils.processTemplateIntoString(template, model);
        helper.setText(html, true);
        //????????????
        javaMailSender.send(mimeMessage);
        valueOperations.set(RESET_PASSWORD_CODE_PREFIX + email, code, 10, TimeUnit.MINUTES);
    }

    /**
     * ???????????????????????????
     *
     * @param mode     0??????????????????1???????????????
     * @param account  ???????????????
     * @param code     ?????????
     * @param password ?????????
     * @return
     */
    @RequestMapping(value = "/reset/login/password", method = RequestMethod.POST)
    @ResponseBody
    @Transactional(rollbackFor = Exception.class)
    public MessageResult forgetPassword(int mode, String account, String code, String password) throws Exception {
        Member member = null;
        ValueOperations valueOperations = redisTemplate.opsForValue();
        Object redisCode = valueOperations.get(SysConstant.RESET_PASSWORD_CODE_PREFIX + account);
        notNull(redisCode, localeMessageSourceService.getMessage("VERIFICATION_CODE_NOT_EXISTS"));
        if (mode == 0) {
            member = memberService.findByPhone(account);
        } else if (mode == 1) {
            member = memberService.findByEmail(account);
        }
        isTrue(password.length() >= 6 && password.length() <= 20, localeMessageSourceService.getMessage("PASSWORD_LENGTH_ILLEGAL"));
        notNull(member, localeMessageSourceService.getMessage("MEMBER_NOT_EXISTS"));
        if (!code.equals(redisCode.toString())) {
            return error(localeMessageSourceService.getMessage("VERIFICATION_CODE_INCORRECT"));
        } else {
            valueOperations.getOperations().delete(SysConstant.RESET_PASSWORD_CODE_PREFIX + account);
        }
        //????????????
        String newPassword = Md5.md5Digest(password + member.getSalt()).toLowerCase();
        member.setPassword(newPassword);
        return success();
    }
}
