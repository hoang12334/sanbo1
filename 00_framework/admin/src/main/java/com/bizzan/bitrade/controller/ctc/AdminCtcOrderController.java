package com.bizzan.bitrade.controller.ctc;

import static org.springframework.util.Assert.notNull;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.bizzan.bitrade.util.StringUtil;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.apache.shiro.util.Assert;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

import com.bizzan.bitrade.annotation.AccessLog;
import com.bizzan.bitrade.constant.AdminModule;
import com.bizzan.bitrade.constant.PageModel;
import com.bizzan.bitrade.constant.SysConstant;
import com.bizzan.bitrade.constant.TransactionType;
import com.bizzan.bitrade.controller.BaseController;
import com.bizzan.bitrade.entity.Admin;
import com.bizzan.bitrade.entity.CtcAcceptor;
import com.bizzan.bitrade.entity.CtcOrder;
import com.bizzan.bitrade.entity.Member;
import com.bizzan.bitrade.entity.MemberTransaction;
import com.bizzan.bitrade.entity.MemberWallet;
import com.bizzan.bitrade.service.CtcAcceptorService;
import com.bizzan.bitrade.service.CtcOrderService;
import com.bizzan.bitrade.service.LocaleMessageSourceService;
import com.bizzan.bitrade.service.MemberService;
import com.bizzan.bitrade.service.MemberTransactionService;
import com.bizzan.bitrade.service.MemberWalletService;
import com.bizzan.bitrade.util.DateUtil;
import com.bizzan.bitrade.util.MessageResult;
import com.bizzan.bitrade.vendor.provider.SMSProvider;
import com.sparkframework.security.Encrypt;

/**
 * @author Shaoxianjun
 * @description otc??????
 * @date 2019/1/11 13:35
 */
@RestController
@RequestMapping("/ctc/order")
public class AdminCtcOrderController extends BaseController {

    @Autowired
    private CtcOrderService ctcOrderService;

    @Autowired
    private MemberWalletService memberWalletService;

    @Autowired
    private MemberTransactionService memberTransactionService;

    @Autowired
    private LocaleMessageSourceService messageSource;

    @Autowired
    private CtcAcceptorService acceptorService;

    @Value("${spark.system.md5.key}")
    private String md5Key;

    @Autowired
    private SMSProvider smsProvider;

    @Autowired
    private MemberService memberService;

    /**
     * ????????????
     *
     * @param pageModel
     * @return
     */
    @RequiresPermissions("ctc:order:page-query")
    @PostMapping("page-query")
    @AccessLog(module = AdminModule.CTC, operation = "????????????CTC????????????AdminCtcOrderController")
    public MessageResult orderList(PageModel pageModel) {
        if (pageModel.getProperty() == null) {
            List<String> list = new ArrayList<>();
            list.add("createTime");
            List<Sort.Direction> directions = new ArrayList<>();
            directions.add(Sort.Direction.DESC);
            pageModel.setProperty(list);
            pageModel.setDirection(directions);
        }
        Page<CtcOrder> all = ctcOrderService.findAll(null, pageModel.getPageable());
        return success(all);
    }

    /**
     * ????????????
     *
     * @param id
     * @return
     */
    @RequiresPermissions("ctc:order:order-detail")
    @PostMapping("order-detail")
    @AccessLog(module = AdminModule.CTC, operation = "????????????CTC????????????AdminCtcOrderController")
    public MessageResult orderDetail(@RequestParam("id") Long id) {
        if (id == null || id == 0) {
            return error("???????????????");
        }
        CtcOrder order = ctcOrderService.findOne(id);
        if (order == null) {
            return error("???????????????");
        }
        return success(order);
    }

    /**
     * ????????????
     *
     * @param id
     * @param
     * @return
     */
    @RequiresPermissions("ctc:order:pay-order")
    @AccessLog(module = AdminModule.CTC, operation = "????????????????????????CTC??????")
    @PostMapping("pay-order")
    @Transactional(rollbackFor = Exception.class)
    public MessageResult payOrder(@RequestParam("id") Long id,
                                  @RequestParam(value = "password") String password,
                                  @SessionAttribute(SysConstant.SESSION_ADMIN) Admin admin) {

        password = Encrypt.MD5(password + md5Key);
        Assert.isTrue(password.equals(admin.getPassword()), messageSource.getMessage("WRONG_PASSWORD"));

        CtcOrder order = ctcOrderService.findOne(id);
        notNull(order, "validate order.id!");
        if (order.getStatus() != 1) {
            return error("?????????????????????????????????????????????????????????");
        }
        if (order.getDirection() != 1) {
            return error("?????????????????????????????????????????????");
        }
        order.setStatus(2);
        order.setPayTime(DateUtil.getCurrentDate());
        ctcOrderService.save(order);
        return success();
    }

    /**
     * ??????(?????????)
     *
     * @param id
     * @param
     * @return
     */
    @RequiresPermissions("ctc:order:complete-order")
    @AccessLog(module = AdminModule.CTC, operation = "????????????????????????CTC??????")
    @PostMapping("complete-order")
    @Transactional(rollbackFor = Exception.class)
    public MessageResult completeOrder(@RequestParam("id") Long id,
                                       @RequestParam(value = "password") String password,
                                       @SessionAttribute(SysConstant.SESSION_ADMIN) Admin admin) {

        password = Encrypt.MD5(password + md5Key);
        System.out.println(password);
        Assert.isTrue(password.equals(admin.getPassword()), messageSource.getMessage("WRONG_PASSWORD"));

        CtcOrder order = ctcOrderService.findOne(id);
        notNull(order, "validate order.id!");

        if (order.getStatus() != 2) {
            return error("???????????????????????????");
        }
        List<CtcAcceptor> acceptors = acceptorService.findByMember(order.getAcceptor());//findOne(order.getAcceptor().getId());
        if (acceptors.size() != 1) {
            return error("??????????????????????????????");
        }
        CtcAcceptor acceptor = acceptors.get(0);
        // ????????????=>????????????????????????
        if (order.getDirection() == 0) {
            MemberWallet mw = memberWalletService.findByCoinUnitAndMemberId(order.getUnit(), order.getMember().getId());
            if (mw == null) {
                return error("?????????????????????");
            }
            memberWalletService.increaseBalance(mw.getId(), order.getAmount());

            MemberTransaction memberTransaction = new MemberTransaction();
            memberTransaction.setFee(BigDecimal.ZERO);
            memberTransaction.setAmount(order.getAmount());
            memberTransaction.setMemberId(mw.getMemberId());
            memberTransaction.setSymbol(order.getUnit());
            memberTransaction.setType(TransactionType.CTC_BUY);
            memberTransaction.setCreateTime(DateUtil.getCurrentDate());
            memberTransaction.setRealFee("0");
            memberTransaction.setDiscountFee("0");
            memberTransaction = memberTransactionService.save(memberTransaction);

            // ????????????????????????USDT???CNY???
            acceptor.setUsdtOut(acceptor.getUsdtOut().add(order.getAmount())); // ??????USDT??????
            acceptor.setCnyIn(acceptor.getCnyIn().add(order.getMoney())); // ?????????????????????
            acceptorService.saveAndFlush(acceptor);

            Member member = memberService.findOne(order.getMember().getId());
            try {
                String orderSn = StringUtil.getLastSixNum(order.getOrderSn());
                smsProvider.sendCustomMessage(member.getMobilePhone(), "????????????????????????????????????" + orderSn + ",???????????????" + order.getPrice() + ",?????????" + order.getAmount() + ",?????????" + order.getMoney() + "????????????????????????????????????????????????????????????");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // ????????????=>??????????????????????????????
        if (order.getDirection() == 1) {
            MemberWallet mw = memberWalletService.findByCoinUnitAndMemberId(order.getUnit(), order.getMember().getId());
            if (mw == null) {
                return error("?????????????????????");
            }
            if (mw.getFrozenBalance().compareTo(order.getAmount()) < 0) {
                return error("????????????????????????");
            }
            memberWalletService.decreaseFrozen(mw.getId(), order.getAmount());


            MemberTransaction memberTransaction = new MemberTransaction();
            memberTransaction.setFee(BigDecimal.ZERO);
            memberTransaction.setAmount(order.getAmount().negate());
            memberTransaction.setMemberId(mw.getMemberId());
            memberTransaction.setSymbol(order.getUnit());
            memberTransaction.setType(TransactionType.CTC_SELL);
            memberTransaction.setCreateTime(DateUtil.getCurrentDate());
            memberTransaction.setRealFee("0");
            memberTransaction.setDiscountFee("0");
            memberTransaction = memberTransactionService.save(memberTransaction);

            acceptor.setUsdtIn(acceptor.getUsdtIn().add(order.getAmount())); // ??????USDT??????
            acceptor.setCnyOut(acceptor.getCnyOut().add(order.getMoney())); // ?????????????????????
            acceptorService.saveAndFlush(acceptor);

            Member member = memberService.findOne(order.getMember().getId());
            try {
                String orderSn = StringUtil.getLastSixNum(order.getOrderSn());
                smsProvider.sendCustomMessage(member.getMobilePhone(), "????????????????????????????????????" + orderSn + ",???????????????" + order.getPrice() + ",?????????" + order.getAmount() + ",?????????" + order.getMoney() + "????????????????????????????????????????????????????????????");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        order.setStatus(3);
        order.setCompleteTime(DateUtil.getCurrentDate());
        ctcOrderService.save(order);
        return success();
    }

    /**
     * ????????????
     *
     * @param id
     * @param
     * @return
     */
    @RequiresPermissions("ctc:order:confirm-order")
    @AccessLog(module = AdminModule.CTC, operation = "????????????")
    @PostMapping("confirm-order")
    @Transactional(rollbackFor = Exception.class)
    public MessageResult confirmOrder(@RequestParam("id") Long id,
                                      @RequestParam(value = "password") String password,
                                      @SessionAttribute(SysConstant.SESSION_ADMIN) Admin admin) {

        password = Encrypt.MD5(password + md5Key);
        Assert.isTrue(password.equals(admin.getPassword()), messageSource.getMessage("WRONG_PASSWORD"));

        CtcOrder order = ctcOrderService.findOne(id);
        notNull(order, "validate order.id!");
        if (order.getStatus() != 0) {
            return error("?????????????????????????????????????????????????????????");
        }
        order.setStatus(1);
        order.setConfirmTime(DateUtil.getCurrentDate());
        ctcOrderService.save(order);

        Member member = memberService.findOne(order.getMember().getId());
        try {
            String orderSn = StringUtil.getLastSixNum(order.getOrderSn());
            smsProvider.sendCustomMessage(member.getMobilePhone(), "????????????????????????????????????" + orderSn + ",?????????" + order.getPrice() + ",?????????" + order.getAmount() + ",?????????" + order.getMoney() + "???????????????????????????????????????????????????????????????");
        } catch (Exception e) {
            e.printStackTrace();
        }

        return success();
    }

    /**
     * ??????????????????
     *
     * @param id
     * @param
     * @return
     */
    @RequiresPermissions("ctc:order:cancel-order")
    @PostMapping("cancel-order")
    @AccessLog(module = AdminModule.CTC, operation = "????????????????????????CTC??????")
    @Transactional(rollbackFor = Exception.class)
    public MessageResult cancelOrder(@RequestParam("id") Long id,
                                     @RequestParam(value = "password") String password,
                                     @SessionAttribute(SysConstant.SESSION_ADMIN) Admin admin) {

        password = Encrypt.MD5(password + md5Key);
        Assert.isTrue(password.equals(admin.getPassword()), messageSource.getMessage("WRONG_PASSWORD"));

        CtcOrder order = ctcOrderService.findOne(id);
        notNull(order, "validate order.id!");

        if (order.getStatus() == 3 || order.getStatus() == 4) {
            return error("????????????????????????????????????");
        }

        if (order.getDirection() == 1) {
            // ?????????????????????????????????
            MemberWallet memberWallet = memberWalletService.findByCoinUnitAndMemberId(order.getUnit(), order.getMember().getId());
            if (memberWallet.getFrozenBalance().compareTo(order.getAmount()) >= 0) {
                memberWalletService.thawBalance(memberWallet, order.getAmount());
            } else {
                return error("???????????????????????????");
            }
        }
        order.setStatus(4);
        order.setCancelReason("?????????????????????");
        order.setCancelTime(DateUtil.getCurrentDate());
        ctcOrderService.save(order);

        Member member = memberService.findOne(order.getMember().getId());
        try {
            String orderSn = StringUtil.getLastSixNum(order.getOrderSn());
            smsProvider.sendCustomMessage(member.getMobilePhone(), "????????????????????????????????????" + orderSn + "?????????????????????????????????");
        } catch (Exception e) {
            e.printStackTrace();
        }
        return success("??????????????????");
    }
}
