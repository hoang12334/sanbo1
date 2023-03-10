package com.bizzan.bitrade.controller;


import com.bizzan.bitrade.constant.BooleanEnum;
import com.bizzan.bitrade.constant.MemberLevelEnum;
import com.bizzan.bitrade.constant.SysConstant;
import com.bizzan.bitrade.constant.TransactionType;
import com.bizzan.bitrade.entity.*;
import com.bizzan.bitrade.entity.transform.AuthMember;
import com.bizzan.bitrade.service.*;
import com.bizzan.bitrade.util.DateUtil;
import com.bizzan.bitrade.util.MessageResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static com.bizzan.bitrade.constant.SysConstant.SESSION_MEMBER;
import static org.springframework.util.Assert.hasText;

@RestController
@RequestMapping("mining")
public class MiningsController {

    @Autowired
    private MiningService miningService;
    @Autowired
    private LocaleMessageSourceService sourceService;
    @Autowired
    private MemberService memberService;
    @Autowired
    private RedisTemplate redisTemplate;
    @Autowired
    private MiningOrderService miningOrderService;
    @Autowired
    private CoinService coinService;
    @Autowired
    private MemberWalletService walletService;
    @Autowired
    private MemberTransactionService memberTransactionService;


    @RequestMapping("page-query")
    public MessageResult page(int pageNo, int pageSize) {
        MessageResult mr = new MessageResult();
        Page<Mining> all = miningService.queryAll(pageNo, pageSize);
        mr.setCode(0);
        mr.setData(all);
        return mr;
    }

    @RequestMapping("detail")
    public MessageResult detail(Long id) {
        Mining detail = miningService.findOne(id);
        Assert.notNull(detail, "validate id!");
        MessageResult mr = new MessageResult();
        mr.setData(detail);
        return mr;
    }

    @PostMapping("buy")
    @Transactional(rollbackFor = Exception.class)
    public MessageResult buy(@SessionAttribute(SESSION_MEMBER) AuthMember user,
                             BigDecimal amount,
                             Long miningId,
                             String code,
                             String aims) {
        Mining mining = miningService.findOne(miningId);
        Assert.notNull(mining, "validate id!");
        if (mining.getStatus() == BooleanEnum.IS_FALSE) {
            return MessageResult.error("??????????????????");
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return MessageResult.error(500, sourceService.getMessage("NUMBER_OF_ILLEGAL"));
        }
        //???????????????
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
            return MessageResult.error(" ???????????????");
        }
        // ????????????????????????
        if (member.getMemberLevel() == MemberLevelEnum.GENERAL) {
            return MessageResult.error(500, "????????????????????????");
        }
        //?????????????????????
        if (member.getTransactionStatus().equals(BooleanEnum.IS_FALSE)) {
            return MessageResult.error(sourceService.getMessage("?????????????????????"));
        }
        // ??????????????????????????????
        if (mining.getLeveloneCount() > 0) {
            if (member.getFirstLevel() < mining.getLeveloneCount()) {
                return MessageResult.error(500, "??????????????????????????????" + mining.getLeveloneCount());
            }
        }
        // ???????????????
        if (mining.getMinLimitAmout().compareTo(BigDecimal.ZERO) > 0) {
            if (mining.getMinLimitAmout().compareTo(amount) > 0) {
                return MessageResult.error("??????????????????????????????");
            }
        }
        //???????????????
        if (mining.getMaxLimitAmout().compareTo(BigDecimal.ZERO) > 0) {
            if (mining.getMaxLimitAmout().compareTo(amount) < 0) {
                return MessageResult.error("???????????????????????????");
            }
        }
        //??????????????????
        List<MiningOrder> miningOrderList = miningOrderService.findAllByMemberIdAndActivityId(user.getId(), miningId);
        if (mining.getLimitTimes() > 0 && miningOrderList != null) {
            Integer alreadyBuyAmount = miningOrderList.size();
            if (alreadyBuyAmount >= mining.getLimitTimes()) {
                return MessageResult.error("????????????????????????????????????");
            }
        }
        // ????????????????????????
        Coin coin;
        coin = coinService.findByUnit(mining.getAcceptUnit());
        if (coin == null) {
            return MessageResult.error(sourceService.getMessage("NONSUPPORT_COIN"));
        }
        MemberWallet acceptCoinWallet = walletService.findByCoinUnitAndMemberId(mining.getAcceptUnit(), member.getId());
        if (acceptCoinWallet == null || acceptCoinWallet == null) {
            return MessageResult.error(sourceService.getMessage("NONSUPPORT_COIN"));
        }
        if (acceptCoinWallet.getIsLock().equals(BooleanEnum.IS_TRUE)) {
            return MessageResult.error("???????????????");
        }

        //????????????????????????
        String coinType = mining.getAcceptUnit();
        MemberWallet byCoinUnitAndMemberId = walletService.findByCoinUnitAndMemberId(coinType, user.getId());
        BigDecimal userBalance = byCoinUnitAndMemberId.getBalance();
        BigDecimal amountPrice = mining.getPrice().multiply(amount);
        if (userBalance.compareTo(amountPrice) < 0) {
            return MessageResult.error(sourceService.getMessage("INSUFFICIENT_COIN") + mining.getAcceptUnit());
        }
        Integer intAmount = Integer.parseInt(String.valueOf(amount));
        Date currentDate = DateUtil.getCurrentDate();
        for (int i = 0; i < intAmount; i++) {
            MiningOrder mo = new MiningOrder();
            mo.setActivityId(mining.getId());
            mo.setMemberId(user.getId());
            mo.setMiningDays(mining.getMiningDays());
            mo.setMiningDaysprofit(mining.getMiningDaysprofit());
            mo.setMiningUnit(mining.getMiningUnit());
            mo.setCurrentDaysprofit(mining.getMiningDaysprofit());
            mo.setCreateTime(new Date());
            mo.setImage(mining.getSmallImageUrl());
            mo.setTitle(mining.getMiningName());
            mo.setMiningTimes(mining.getMiningTimes());
            mo.setMiningDays(mining.getMiningDays());
            mo.setMiningStatus(1); // ???????????????1???????????????
            mo.setMiningedTimes(0); // ?????????0???
            mo.setMiningTime(DateUtil.dateAddByPeriod(currentDate, mining.getMiningDays(), mining.getMiningPeriod()));
            mo.setTotalProfit(BigDecimal.ZERO);
            mo.setType(0); // ????????????
            mo.setMiningInvite(mining.getMiningInvite()); // ??????
            mo.setMiningInvitelimit(mining.getMiningInvitelimit()); // ????????????
            mo.setPeriod(mining.getMiningPeriod()); // ??????????????????
            mo.setFee(mining.getFee()); // ?????????
            miningOrderService.save(mo);
        }
        //????????????????????????
        walletService.deductBalance(acceptCoinWallet, amountPrice);
        // ????????????
        MemberTransaction memberTransaction1 = new MemberTransaction();
        memberTransaction1.setFee(BigDecimal.ZERO);
        memberTransaction1.setAmount(amountPrice.negate());
        memberTransaction1.setMemberId(user.getId());
        memberTransaction1.setSymbol(mining.getAcceptUnit());
        memberTransaction1.setType(TransactionType.MINING_IN);
        memberTransaction1.setCreateTime(DateUtil.getCurrentDate());
        memberTransaction1.setRealFee("0");
        memberTransaction1.setDiscountFee("0");
        memberTransactionService.save(memberTransaction1);
        //????????????
        memberService.addPower(user.getId(), mining.getPower());
        //????????????????????????
        miningOrderService.addInviterCurrentProfit(user.getId(), miningId);
        return MessageResult.success("????????????????????????");

    }

}
