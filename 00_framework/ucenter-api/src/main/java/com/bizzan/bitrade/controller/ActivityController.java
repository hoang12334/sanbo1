package com.bizzan.bitrade.controller;

import static com.bizzan.bitrade.constant.SysConstant.SESSION_MEMBER;
import static org.springframework.util.Assert.hasText;

import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;

import com.bizzan.bitrade.entity.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

import com.bizzan.bitrade.constant.BooleanEnum;
import com.bizzan.bitrade.constant.MemberLevelEnum;
import com.bizzan.bitrade.constant.SysConstant;
import com.bizzan.bitrade.controller.BaseController;
import com.bizzan.bitrade.entity.transform.AuthMember;
import com.bizzan.bitrade.service.ActivityOrderService;
import com.bizzan.bitrade.service.ActivityService;
import com.bizzan.bitrade.service.CoinService;
import com.bizzan.bitrade.service.LocaleMessageSourceService;
import com.bizzan.bitrade.service.MemberService;
import com.bizzan.bitrade.service.MemberWalletService;
import com.bizzan.bitrade.util.DateUtil;
import com.bizzan.bitrade.util.MessageResult;

@RestController
@RequestMapping("activity")
public class ActivityController extends BaseController {
    @Autowired
    private ActivityService activityService;

    @Autowired
    private ActivityOrderService activityOrderService;

    @Autowired
    private LocaleMessageSourceService sourceService;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private MemberService memberService;

    @Autowired
    private CoinService coinService;

    @Autowired
    private MemberWalletService walletService;

    private SimpleDateFormat dateTimeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    @RequestMapping("page-query")
    public MessageResult page(int pageNo, int pageSize, int step) {
        MessageResult mr = new MessageResult();
        Page<Activity> all = activityService.queryByStep(pageNo, pageSize, step);
        mr.setCode(0);
        mr.setData(all);
        return mr;
    }

    @RequestMapping("detail")
    public MessageResult detail(Long id) {
        Activity detail = activityService.findOne(id);
        Assert.notNull(detail, "validate id!");
        return success(detail);
    }

    @RequestMapping("attend")
    @Transactional(rollbackFor = Exception.class)
    public MessageResult attendActivity(@SessionAttribute(SESSION_MEMBER) AuthMember user,
                                        BigDecimal amount,
                                        Long activityId,
                                        String code,
                                        String aims) {

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return MessageResult.error(500, sourceService.getMessage("NUMBER_OF_ILLEGAL"));
        }
        Assert.notNull(activityId, "valid activity id");
        // ???????????????????????????
        hasText(code, sourceService.getMessage("MISSING_VERIFICATION_CODE"));
        hasText(aims, sourceService.getMessage("MISSING_PHONE_OR_EMAIL"));
        ValueOperations valueOperations = redisTemplate.opsForValue();
        Member member = memberService.findOne(user.getId());
        if (member.getMobilePhone() != null && aims.equals(member.getMobilePhone())) {
            Object info = valueOperations.get(SysConstant.PHONE_ATTEND_ACTIVITY_PREFIX + member.getMobilePhone());
            if (info == null || !info.toString().equals(code)) {
                return MessageResult.error(sourceService.getMessage("VERIFICATION_CODE_INCORRECT"));
            } else {
                valueOperations.getOperations().delete(SysConstant.PHONE_ATTEND_ACTIVITY_PREFIX + member.getMobilePhone());
            }
        } else if (member.getEmail() != null && aims.equals(member.getEmail())) {
            Object info = valueOperations.get(SysConstant.PHONE_ATTEND_ACTIVITY_PREFIX + member.getEmail());
            if (!info.toString().equals(code)) {
                return MessageResult.error(sourceService.getMessage("VERIFICATION_CODE_INCORRECT"));
            } else {
                valueOperations.getOperations().delete(SysConstant.PHONE_ATTEND_ACTIVITY_PREFIX + member.getEmail());
            }
        } else {
            return MessageResult.error("?????????????????????");
        }

        // ????????????????????????
        if (member.getMemberLevel() == MemberLevelEnum.GENERAL) {
            return MessageResult.error(500, "????????????????????????");
        }

        //?????????????????????
        if (member.getTransactionStatus().equals(BooleanEnum.IS_FALSE)) {
            return MessageResult.error(sourceService.getMessage("CANNOT_TRADE"));
        }

        // ????????????????????????
        Activity activity = activityService.findOne(activityId);
        Assert.notNull(activity, "??????????????????!");

        // ??????????????????????????????
        if (activity.getLeveloneCount() > 0) {
            if (member.getFirstLevel() < activity.getLeveloneCount()) {
                return MessageResult.error(500, "??????????????????????????????" + activity.getLeveloneCount());
            }
        }
        // ???????????????
        if (activity.getType() == 5) {
            if (amount.intValue() < 1) {
                return MessageResult.error("?????????????????????????????????");
            }
        }
        //????????????????????????????????????
        if (activity.getType() == 1 || activity.getType() == 2 || activity.getType() == 0) {
            return MessageResult.error("????????????????????????????????????????????????");
        }
        // ??????????????????????????????
        if (activity.getStep() != 1) {
            return MessageResult.error("??????????????????????????????");
        }
        // ?????????????????????????????????????????????
        long currentTime = Calendar.getInstance().getTimeInMillis(); // ???????????????
        try {
            if (dateTimeFormat.parse(activity.getEndTime()).getTime() < currentTime) {
                return MessageResult.error("??????????????????");
            }
            if (dateTimeFormat.parse(activity.getStartTime()).getTime() > currentTime) {
                return MessageResult.error("?????????????????????");
            }
        } catch (ParseException e) {
            e.printStackTrace();
            return MessageResult.error("?????????????????????Code???9901");
        }

        // ??????????????????????????????????????????????????????
        if (activity.getType() == 4 || activity.getType() == 5) {
            if (activity.getTradedAmount().compareTo(activity.getTotalSupply()) >= 0) {
                return MessageResult.error("????????????????????????");
            }
        }
        // ???????????????/???????????????
        if (activity.getMinLimitAmout().compareTo(BigDecimal.ZERO) > 0) {
            if (activity.getMinLimitAmout().compareTo(amount) > 0) {
                return MessageResult.error("??????????????????????????????");
            }
        }
        if (activity.getMaxLimitAmout().compareTo(BigDecimal.ZERO) > 0 || activity.getLimitTimes() > 0) {
            // ???????????????/???????????????(????????????????????????)
            List<ActivityOrder> orderDetailList = activityOrderService.findAllByActivityIdAndMemberId(member.getId(), activityId);
            BigDecimal alreadyAttendAmount = BigDecimal.ZERO;
            int alreadyAttendTimes = 0;
            if (orderDetailList != null) {
                alreadyAttendTimes = orderDetailList.size();
                for (int i = 0; i < orderDetailList.size(); i++) {
                    if (activity.getType() == 3) {
                        alreadyAttendAmount = alreadyAttendAmount.add(orderDetailList.get(i).getFreezeAmount());
                    } else {
                        alreadyAttendAmount = alreadyAttendAmount.add(orderDetailList.get(i).getAmount());
                    }
                }
            }
            // ???????????????
            if (activity.getMaxLimitAmout().compareTo(BigDecimal.ZERO) > 0) {
                if (alreadyAttendAmount.add(amount).compareTo(activity.getMaxLimitAmout()) > 0) {
                    return MessageResult.error("??????????????????????????????");
                }
            }
            // ??????????????????
            if (activity.getLimitTimes() > 0) {
                if (activity.getLimitTimes() < alreadyAttendTimes + 1) {
                    return MessageResult.error("?????????????????????");
                }
            }
        }

        // ??????????????????
        if (activity.getHoldLimit().compareTo(BigDecimal.ZERO) > 0 && activity.getHoldUnit() != null && activity.getHoldUnit() != "") {
            MemberWallet holdCoinWallet = walletService.findByCoinUnitAndMemberId(activity.getHoldUnit(), member.getId());
            if (holdCoinWallet == null) {
                return MessageResult.error("??????????????????????????????");
            }
            if (holdCoinWallet.getIsLock().equals(BooleanEnum.IS_TRUE)) {
                return MessageResult.error("??????????????????????????????");
            }
            if (holdCoinWallet.getBalance().compareTo(activity.getHoldLimit()) < 0) {
                return MessageResult.error("??????" + activity.getHoldUnit() + "??????????????????????????????");
            }
        }

        // ????????????????????????
        Coin coin;
        coin = coinService.findByUnit(activity.getAcceptUnit());
        if (coin == null) {
            return MessageResult.error(sourceService.getMessage("NONSUPPORT_COIN"));
        }

        // ????????????????????????
        MemberWallet acceptCoinWallet = walletService.findByCoinUnitAndMemberId(activity.getAcceptUnit(), member.getId());
        if (acceptCoinWallet == null || acceptCoinWallet == null) {
            return MessageResult.error(sourceService.getMessage("NONSUPPORT_COIN"));
        }
        if (acceptCoinWallet.getIsLock().equals(BooleanEnum.IS_TRUE)) {
            return MessageResult.error("???????????????");
        }

        // ????????????
        BigDecimal totalAcceptCoinAmount = BigDecimal.ZERO;
        if (activity.getType() == 3) { // ????????????
            totalAcceptCoinAmount = amount.setScale(activity.getAmountScale(), BigDecimal.ROUND_HALF_DOWN);
        } else if (activity.getType() == 4) {  // ????????????
            totalAcceptCoinAmount = activity.getPrice().multiply(amount).setScale(activity.getAmountScale(), BigDecimal.ROUND_HALF_DOWN);
        } else if (activity.getType() == 5) {  // ????????????
            totalAcceptCoinAmount = activity.getPrice().multiply(amount).setScale(activity.getAmountScale(), BigDecimal.ROUND_HALF_DOWN);
        }
        // ??????????????????
        if (activity.getType() == 4 && activity.getDiscount() == BooleanEnum.IS_TRUE) {
            BigDecimal discount = activityService.findDiscountByLevel(activity.getId(), member.getLevel().getId());
            if (discount != null && discount.compareTo(BigDecimal.ZERO) > 0 && discount.compareTo(BigDecimal.TEN) < 0) {
                totalAcceptCoinAmount = totalAcceptCoinAmount.multiply(discount).divide(BigDecimal.TEN, activity.getPriceScale(), BigDecimal.ROUND_DOWN);
            }
        }
        // ????????????????????????
        if (acceptCoinWallet.getBalance().compareTo(totalAcceptCoinAmount) < 0) {
            return MessageResult.error(sourceService.getMessage("INSUFFICIENT_COIN") + activity.getAcceptUnit());
        }

        ActivityOrder activityOrder = new ActivityOrder();
        activityOrder.setActivityId(activityId);
        if (activity.getType() == 3) {
            activityOrder.setAmount(BigDecimal.ZERO);
            activityOrder.setFreezeAmount(totalAcceptCoinAmount); // ?????????????????????????????????????????????????????????
        } else if (activity.getType() == 4) {
            activityOrder.setAmount(amount); // ??????????????????
            activityOrder.setFreezeAmount(BigDecimal.ZERO);
        } else if (activity.getType() == 5) {
            activityOrder.setAmount(amount); // ??????????????????
            activityOrder.setFreezeAmount(BigDecimal.ZERO);
        } else {
            activityOrder.setAmount(BigDecimal.ZERO);
            activityOrder.setFreezeAmount(BigDecimal.ZERO);
        }
        activityOrder.setBaseSymbol(activity.getAcceptUnit());
        activityOrder.setCoinSymbol(activity.getUnit());
        activityOrder.setCreateTime(DateUtil.getCurrentDate());
        activityOrder.setMemberId(member.getId());
        activityOrder.setPrice(activity.getPrice());
        activityOrder.setState(1); //?????????
        activityOrder.setTurnover(totalAcceptCoinAmount);//??????????????????????????????????????????
        activityOrder.setType(activity.getType());

        MessageResult mr = activityOrderService.saveActivityOrder(member.getId(), activityOrder);

        if (mr.getCode() != 0) {
            return MessageResult.error(500, "??????????????????:" + mr.getMessage());
        } else {
            return MessageResult.success("????????????????????????");
        }
    }

    @RequestMapping("getmemberrecords")
    public MessageResult getMemberRecordsByActivityId(@SessionAttribute(SESSION_MEMBER) AuthMember user, Long activityId) {

        Assert.notNull(activityId, "valid activity id");
        List<ActivityOrder> orderList = activityOrderService.findAllByActivityIdAndMemberId(user.getId(), activityId);

        return success(orderList);
    }

    /**
     * ?????????????????????????????????
     *
     * @param user
     * @param pageNo
     * @param pageSize
     * @return
     */
    @RequestMapping("getmyorders")
    public MessageResult getMemberOrders(@SessionAttribute(SESSION_MEMBER) AuthMember user, @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo, @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize) {

        Assert.notNull(user, "valid user");
        Page<ActivityOrder> orderList = activityOrderService.finaAllByMemberId(user.getId(), pageNo, pageSize);
        for (int i = 0; i < orderList.getContent().size(); i++) {
            Activity item = activityService.findOne(orderList.getContent().get(i).getActivityId());
            if (item != null) {
                orderList.getContent().get(i).setActivityName(item.getTitle());
            }
        }
        return success(orderList);
    }
}
