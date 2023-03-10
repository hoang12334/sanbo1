package com.bizzan.bitrade.service;


import com.alibaba.fastjson.JSONObject;
import com.bizzan.bitrade.constant.BooleanEnum;
import com.bizzan.bitrade.constant.PromotionRewardType;
import com.bizzan.bitrade.constant.RewardRecordType;
import com.bizzan.bitrade.constant.TransactionType;
import com.bizzan.bitrade.dao.ExchangeOrderDetailRepository;
import com.bizzan.bitrade.dao.ExchangeOrderRepository;
import com.bizzan.bitrade.dao.OrderDetailAggregationRepository;
import com.bizzan.bitrade.entity.*;
import com.bizzan.bitrade.pagination.Criteria;
import com.bizzan.bitrade.pagination.PageResult;
import com.bizzan.bitrade.pagination.Restrictions;
import com.bizzan.bitrade.service.LocaleMessageSourceService;
import com.bizzan.bitrade.service.MemberService;
import com.bizzan.bitrade.service.MemberTransactionService;
import com.bizzan.bitrade.service.MemberWalletService;
import com.bizzan.bitrade.service.RewardPromotionSettingService;
import com.bizzan.bitrade.service.RewardRecordService;
import com.bizzan.bitrade.service.Base.BaseService;
import com.bizzan.bitrade.util.BigDecimalUtils;
import com.bizzan.bitrade.util.DateUtil;
import com.bizzan.bitrade.util.GeneratorUtil;
import com.bizzan.bitrade.util.MessageResult;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.sparkframework.sql.DB;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

@Slf4j
@Service
public class ExchangeOrderService extends BaseService {

    @Autowired
    private ExchangeOrderRepository exchangeOrderRepository;
    @Autowired
    private MemberWalletService memberWalletService;
    @Autowired
    private ExchangeOrderDetailRepository exchangeOrderDetailRepository;
    @Autowired
    private ExchangeCoinService exchangeCoinService;
    @Autowired
    private OrderDetailAggregationRepository orderDetailAggregationRepository;
    @Autowired
    private MemberService memberService;
    @Autowired
    private MemberTransactionService transactionService;
    @Autowired
    private RewardPromotionSettingService rewardPromotionSettingService;
    @Autowired
    private RewardRecordService rewardRecordService;
    @Autowired
    private ExchangeOrderDetailService exchangeOrderDetailService;
    @Value("${channel.enable:false}")
    private Boolean channelEnable;
    @Value("${channel.exchange-rate:0.00}")
    private BigDecimal channelExchangeRate;

    @Autowired
    private LocaleMessageSourceService msService;

    public Page<ExchangeOrder> findAll(Predicate predicate, Pageable pageable) {
        return exchangeOrderRepository.findAll(predicate, pageable);
    }


    /**
     * ??????????????????
     *
     * @param memberId
     * @param order
     * @return
     */
    @Transactional
    public MessageResult addOrder(Long memberId, ExchangeOrder order) {
        order.setTime(Calendar.getInstance().getTimeInMillis());
        order.setStatus(ExchangeOrderStatus.TRADING);
        order.setTradedAmount(BigDecimal.ZERO);
        order.setOrderId(GeneratorUtil.getOrderId("E"));
//        log.info("add order:{}", order);
        if (order.getDirection() == ExchangeOrderDirection.BUY) {
            MemberWallet wallet = memberWalletService.findByCoinUnitAndMemberId(order.getBaseSymbol(), memberId);
            if (wallet.getIsLock().equals(BooleanEnum.IS_TRUE)) {
                return MessageResult.error("???????????????");
            }
            BigDecimal turnover;
            if (order.getType() == ExchangeOrderType.MARKET_PRICE) {
                turnover = order.getAmount();
            } else {
                turnover = order.getAmount().multiply(order.getPrice());
            }
            if (wallet.getBalance().compareTo(turnover) < 0) {
                return MessageResult.error(500, "" +
                        "" + order.getBaseSymbol());
            } else {
                MessageResult result = memberWalletService.freezeBalance(wallet, turnover);
                if (result.getCode() != 0) {
                    return MessageResult.error(500, msService.getMessage("INSUFFICIENT_COIN") + order.getBaseSymbol());
                }
            }
        } else if (order.getDirection() == ExchangeOrderDirection.SELL) {
            MemberWallet wallet = memberWalletService.findByCoinUnitAndMemberId(order.getCoinSymbol(), memberId);
            if (wallet.getIsLock().equals(BooleanEnum.IS_TRUE)) {
                return MessageResult.error("???????????????");
            }
            if (wallet.getBalance().compareTo(order.getAmount()) < 0) {
                return MessageResult.error(500, msService.getMessage("INSUFFICIENT_COIN") + order.getCoinSymbol());
            } else {
                MessageResult result = memberWalletService.freezeBalance(wallet, order.getAmount());
                if (result.getCode() != 0) {
                    return MessageResult.error(500, msService.getMessage("INSUFFICIENT_COIN") + order.getCoinSymbol());
                }
            }
        }
        order = exchangeOrderRepository.saveAndFlush(order);
        if (order != null) {
            return MessageResult.success("success");
        } else {
            return MessageResult.error(500, "error");
        }
    }

    /**
     * @param uid
     * @param pageNo
     * @param pageSize
     * @return
     */
    public Page<ExchangeOrder> findHistory(Long uid, String symbol, int pageNo, int pageSize) {
        Sort orders = new Sort(new Sort.Order(Sort.Direction.DESC, "time"));
        PageRequest pageRequest = new PageRequest(pageNo, pageSize, orders);
        Criteria<ExchangeOrder> specification = new Criteria<ExchangeOrder>();
        specification.add(Restrictions.eq("symbol", symbol, true));
        specification.add(Restrictions.eq("memberId", uid, true));
        specification.add(Restrictions.ne("status", ExchangeOrderStatus.TRADING, false));
        return exchangeOrderRepository.findAll(specification, pageRequest);
    }

    /**
     * ???????????????????????????????????????
     *
     * @param beforeTime
     * @return
     */
    public List<ExchangeOrder> queryHistoryDelete(long beforeTime) {
        return exchangeOrderRepository.queryHistoryDeleteList(beforeTime);
    }

    /**
     * ?????????????????????
     *
     * @param beforeTime
     * @return
     */
    public int deleteHistory(long beforeTime) {
        return exchangeOrderRepository.deleteHistory(beforeTime);
    }

    /**
     * ????????????????????????
     *
     * @param uid
     * @param symbol
     * @param type
     * @param status
     * @param startTime
     * @param endTime
     * @param pageNo
     * @param pageSize
     * @return
     */
    public Page<ExchangeOrder> findPersonalHistory(Long uid, String symbol, ExchangeOrderType type, ExchangeOrderStatus status, String startTime, String endTime, ExchangeOrderDirection direction, int pageNo, int pageSize) {
        Sort orders = new Sort(new Sort.Order(Sort.Direction.DESC, "time"));
        PageRequest pageRequest = new PageRequest(pageNo - 1, pageSize, orders);
        Criteria<ExchangeOrder> specification = new Criteria<ExchangeOrder>();
        if (StringUtils.isNotEmpty(symbol)) {
            specification.add(Restrictions.eq("symbol", symbol, true));
        }
        if (type != null && StringUtils.isNotEmpty(type.toString())) {
            specification.add(Restrictions.eq("type", type, true));
        }
        if (direction != null && StringUtils.isNotEmpty(direction.toString())) {
            specification.add(Restrictions.eq("direction", direction, true));
        }
        specification.add(Restrictions.eq("memberId", uid, true));
        if (StringUtils.isNotEmpty(startTime) && StringUtils.isNotEmpty(endTime)) {
            specification.add(Restrictions.gte("time", Long.valueOf(startTime), true));
            specification.add(Restrictions.lte("time", Long.valueOf(endTime), true));
        }

        if (status == null) {
            specification.add(Restrictions.ne("status", ExchangeOrderStatus.TRADING, false));
        } else {
            specification.add(Restrictions.eq("status", status, true));
        }

        return exchangeOrderRepository.findAll(specification, pageRequest);
    }


    /**
     * ????????????????????????
     *
     * @param uid
     * @param symbol
     * @param type
     * @param startTime
     * @param endTime
     * @param pageNo
     * @param pageSize
     * @return
     */
    public Page<ExchangeOrder> findPersonalCurrent(Long uid, String symbol, ExchangeOrderType type, String startTime, String endTime, ExchangeOrderDirection direction, int pageNo, int pageSize) {
        Sort orders = new Sort(new Sort.Order(Sort.Direction.DESC, "time"));
        PageRequest pageRequest = new PageRequest(pageNo - 1, pageSize, orders);
        Criteria<ExchangeOrder> specification = new Criteria<ExchangeOrder>();
        if (StringUtils.isNotEmpty(symbol)) {
            specification.add(Restrictions.eq("symbol", symbol, true));
        }
        if (type != null && StringUtils.isNotEmpty(type.toString())) {
            specification.add(Restrictions.eq("type", type, true));
        }
        specification.add(Restrictions.eq("memberId", uid, false));
        if (StringUtils.isNotEmpty(startTime) && StringUtils.isNotEmpty(endTime)) {
            specification.add(Restrictions.gte("time", Long.valueOf(startTime), true));
            specification.add(Restrictions.lte("time", Long.valueOf(endTime), true));
        }
        if (direction != null && StringUtils.isNotEmpty(direction.toString())) {
            specification.add(Restrictions.eq("direction", direction, true));
        }
        specification.add(Restrictions.eq("status", ExchangeOrderStatus.TRADING, false));
        return exchangeOrderRepository.findAll(specification, pageRequest);
    }

    /**
     * ??????????????????????????????
     *
     * @param uid
     * @param pageNo
     * @param pageSize
     * @return
     */
    public Page<ExchangeOrder> findCurrent(Long uid, String symbol, int pageNo, int pageSize) {
        Sort orders = new Sort(new Sort.Order(Sort.Direction.DESC, "time"));
        PageRequest pageRequest = new PageRequest(pageNo, pageSize, orders);
        Criteria<ExchangeOrder> specification = new Criteria<ExchangeOrder>();
        specification.add(Restrictions.eq("symbol", symbol, true));
        specification.add(Restrictions.eq("memberId", uid, false));
        specification.add(Restrictions.eq("status", ExchangeOrderStatus.TRADING, false));
        return exchangeOrderRepository.findAll(specification, pageRequest);
    }


    /**
     * ??????????????????
     *
     * @param trade
     * @param secondReferrerAward ????????????????????????????????? true ????????????
     * @return
     * @throws Exception
     */
    @Transactional
    public MessageResult processExchangeTrade(ExchangeTrade trade, boolean secondReferrerAward) throws Exception {
        log.info("processExchangeTrade,trade = {}", trade);
        if (trade == null || trade.getBuyOrderId() == null || trade.getSellOrderId() == null) {
            return MessageResult.error(500, "trade is null");
        }
        ExchangeOrder buyOrder = exchangeOrderRepository.findByOrderId(trade.getBuyOrderId());
        ExchangeOrder sellOrder = exchangeOrderRepository.findByOrderId(trade.getSellOrderId());
        if (buyOrder == null || sellOrder == null) {
            log.error("order not found");
            return MessageResult.error(500, "order not found");
        }
        //??????????????????
        ExchangeCoin coin = exchangeCoinService.findBySymbol(buyOrder.getSymbol());
        if (coin == null) {
            log.error("invalid trade symbol {}", buyOrder.getSymbol());
            return MessageResult.error(500, "invalid trade symbol {}" + buyOrder.getSymbol());
        }
        // ??????memberId????????????????????? 
        DB.query("select id from member_wallet where member_id = ? for update;", buyOrder.getMemberId());
        if (!buyOrder.getMemberId().equals(sellOrder.getMemberId())) {
            DB.query("select id from member_wallet where member_id = ? for update;", sellOrder.getMemberId());
        }
        //?????????????????? ????????? ????????????  ????????????usdtRat
        processOrder(buyOrder, trade, coin, secondReferrerAward);
        //?????????????????? ????????????????????? ????????????usdtRat
        processOrder(sellOrder, trade, coin, secondReferrerAward);
        return MessageResult.success("process success");
    }

    /**
     * ?????????????????????????????????????????????
     *
     * @param order               ????????????
     * @param trade               ????????????
     * @param coin                ???????????????????????????????????? ????????????????????????
     * @param secondReferrerAward ???????????????????????????
     * @return
     */
    public void processOrder(ExchangeOrder order, ExchangeTrade trade, ExchangeCoin coin, boolean secondReferrerAward) {
        try {
            Long time = Calendar.getInstance().getTimeInMillis();
            //??????????????????
            ExchangeOrderDetail orderDetail = new ExchangeOrderDetail();
            orderDetail.setOrderId(order.getOrderId());
            orderDetail.setTime(time);
            orderDetail.setPrice(trade.getPrice());
            orderDetail.setAmount(trade.getAmount());

            BigDecimal incomeCoinAmount, turnover, outcomeCoinAmount;
            if (order.getDirection() == ExchangeOrderDirection.BUY) {
                turnover = trade.getBuyTurnover();
            } else {
                turnover = trade.getSellTurnover();
            }
            orderDetail.setTurnover(turnover);
            //??????????????????????????????coin,??????????????????baseCoin
            BigDecimal fee;
            if (order.getDirection() == ExchangeOrderDirection.BUY) {
                fee = trade.getAmount().multiply(coin.getFee());
            } else {
                fee = turnover.multiply(coin.getFee());
            }
            // ID???1???????????????????????????????????????????????????ID????????????????????????????????????
            // ID???10001?????????????????????????????????????????????????????????ID????????????????????????????????????
            if (order.getMemberId() == 1 || order.getMemberId() == 10001) {
                fee = BigDecimal.ZERO;
            }
            orderDetail.setFee(fee);
            exchangeOrderDetailRepository.save(orderDetail);

            /**
             * ?????????????????????????????????????????????mongodb
             */
            OrderDetailAggregation aggregation = new OrderDetailAggregation();
            aggregation.setType(OrderTypeEnum.EXCHANGE);
            aggregation.setAmount(order.getAmount().doubleValue());
            aggregation.setFee(orderDetail.getFee().doubleValue());
            aggregation.setTime(orderDetail.getTime());
            aggregation.setDirection(order.getDirection());
            aggregation.setOrderId(order.getOrderId());
            if (order.getDirection() == ExchangeOrderDirection.BUY) {
                aggregation.setUnit(order.getBaseSymbol());
            } else {
                aggregation.setUnit(order.getCoinSymbol());
            }
            Member member = memberService.findOne(order.getMemberId());
            if (member != null) {
                aggregation.setMemberId(member.getId());
                aggregation.setUsername(member.getUsername());
                aggregation.setRealName(member.getRealName());
            }
            orderDetailAggregationRepository.save(aggregation);

            //???????????????????????????,??????????????????????????????????????????????????????????????????????????????????????????
            if (order.getDirection() == ExchangeOrderDirection.BUY) {
//                incomeCoinAmount = trade.getAmount().subtract(fee); kickout
                incomeCoinAmount = trade.getAmount();
            } else {
//                incomeCoinAmount = turnover.subtract(fee); kickout
                incomeCoinAmount = turnover;
            }
            String incomeSymbol = order.getDirection() == ExchangeOrderDirection.BUY ? order.getCoinSymbol() : order.getBaseSymbol();
            MemberWallet incomeWallet = memberWalletService.findByCoinUnitAndMemberId(incomeSymbol, order.getMemberId());
            memberWalletService.increaseBalance(incomeWallet.getId(), incomeCoinAmount);
            //????????????????????????????????????????????????????????????????????????
            String outcomeSymbol = order.getDirection() == ExchangeOrderDirection.BUY ? order.getBaseSymbol() : order.getCoinSymbol();
            if (order.getDirection() == ExchangeOrderDirection.BUY) {
                outcomeCoinAmount = turnover;
            } else {
                outcomeCoinAmount = trade.getAmount();
            }
            MemberWallet outcomeWallet = memberWalletService.findByCoinUnitAndMemberId(outcomeSymbol, order.getMemberId());
            memberWalletService.decreaseFrozen(outcomeWallet.getId(), outcomeCoinAmount);
            //?????????????????????
            MemberTransaction transaction = new MemberTransaction();
            transaction.setAmount(incomeCoinAmount);
            transaction.setSymbol(incomeSymbol);
            transaction.setAddress("");
            transaction.setMemberId(incomeWallet.getMemberId());
            transaction.setType(TransactionType.EXCHANGE);
            //????????????
            transaction.setFee(fee);
            //???????????????
            transaction.setDiscountFee("0");
            //???????????????
            transaction.setRealFee(fee.toString());
            transactionService.save(transaction);

            //?????????????????????
            MemberTransaction transaction2 = new MemberTransaction();
            transaction2.setAmount(outcomeCoinAmount.negate());
            transaction2.setSymbol(outcomeSymbol);
            transaction2.setAddress("");
            transaction2.setMemberId(incomeWallet.getMemberId());
            transaction2.setType(TransactionType.EXCHANGE);
            transaction2.setFee(BigDecimal.ZERO);
            transaction2.setRealFee("0");
            transaction2.setDiscountFee("0");
            transactionService.save(transaction2);
            try {
                // ????????????????????????????????????
                if (order.getDirection() == ExchangeOrderDirection.SELL) {
                    promoteReward(fee, member, incomeSymbol, secondReferrerAward);
                }
            } catch (Exception e) {
                e.printStackTrace();
                log.error("?????????????????????????????????????????????", e);
            }
        } catch (Exception e) {
            log.info(">>>>>????????????????????????>>>>>>>>>{}", e);
            e.printStackTrace();
        }
    }

    public List<ExchangeOrderDetail> getAggregation(String orderId) {
        return exchangeOrderDetailService.findAllByOrderId(orderId);
    }


    /**
     * ????????????????????????  ????????? ??????
     * @param member
     * @param symbol
     * @param fee
     */
//    public void processChannelReward(Member member,String symbol,BigDecimal fee){
//        MemberWallet channelWallet =  memberWalletService.findByCoinUnitAndMemberId(symbol,member.getChannelId());
//        if(channelWallet != null && fee.compareTo(BigDecimal.ZERO) > 0 ){
//            BigDecimal amount = fee.multiply(channelExchangeRate);
//            memberWalletService.increaseBalance(channelWallet.getId(),amount);
//            MemberTransaction memberTransaction = new MemberTransaction();
//            memberTransaction.setAmount(amount);
//            memberTransaction.setFee(BigDecimal.ZERO);
//            memberTransaction.setMemberId(member.getChannelId());
//            memberTransaction.setSymbol(symbol);
//            memberTransaction.setType(TransactionType.CHANNEL_AWARD);
//            transactionService.save(memberTransaction);
//        }
//    }

    /**
     * ????????????????????????
     *
     * @param fee                 ?????????
     * @param member              ???????????????
     * @param incomeSymbol        ??????
     * @param secondReferrerAward ?????????????????????????????????
     */
    public void promoteReward(BigDecimal fee, Member member, String incomeSymbol, boolean secondReferrerAward) {
        RewardPromotionSetting rewardPromotionSetting = rewardPromotionSettingService.findByType(PromotionRewardType.EXCHANGE_TRANSACTION);
        if (rewardPromotionSetting != null && member.getInviterId() != null) {
            if (!(DateUtil.diffDays(new Date(), member.getRegistrationTime()) > rewardPromotionSetting.getEffectiveTime())) {
                Member member1 = memberService.findOne(member.getInviterId());
                MemberWallet memberWallet = memberWalletService.findByCoinUnitAndMemberId(incomeSymbol, member1.getId());
                JSONObject jsonObject = JSONObject.parseObject(rewardPromotionSetting.getInfo());
                BigDecimal reward = BigDecimalUtils.mulRound(fee, BigDecimalUtils.getRate(jsonObject.getBigDecimal("one")), 8);
                if (reward.compareTo(BigDecimal.ZERO) > 0) {
                    memberWalletService.increaseBalance(memberWallet.getId(), reward);
                    MemberTransaction memberTransaction = new MemberTransaction();
                    memberTransaction.setAmount(reward);
                    memberTransaction.setFee(BigDecimal.ZERO);
                    memberTransaction.setMemberId(member1.getId());
                    memberTransaction.setSymbol(incomeSymbol);
                    memberTransaction.setType(TransactionType.PROMOTION_AWARD);
                    memberTransaction.setDiscountFee("0");
                    memberTransaction.setRealFee("0");
                    memberTransaction = transactionService.save(memberTransaction);
                    RewardRecord rewardRecord1 = new RewardRecord();
                    rewardRecord1.setAmount(reward);
                    rewardRecord1.setCoin(memberWallet.getCoin());
                    rewardRecord1.setMember(member1);
                    rewardRecord1.setRemark(rewardPromotionSetting.getType().getCnName());
                    rewardRecord1.setType(RewardRecordType.PROMOTION);
                    rewardRecordService.save(rewardRecord1);
                }

                // ????????????????????????????????? ??????false???????????????????????????
                if (secondReferrerAward == false) {
                    log.info("???????????? : secondReferrerAward ={} , ???????????????????????????", secondReferrerAward);
                    return;
                }
                if (member1.getInviterId() != null && !(DateUtil.diffDays(new Date(), member1.getRegistrationTime()) > rewardPromotionSetting.getEffectiveTime())) {
                    Member member2 = memberService.findOne(member1.getInviterId());
                    MemberWallet memberWallet1 = memberWalletService.findByCoinUnitAndMemberId(incomeSymbol, member2.getId());
                    BigDecimal reward1 = BigDecimalUtils.mulRound(fee, BigDecimalUtils.getRate(jsonObject.getBigDecimal("two")), 8);
                    if (reward1.compareTo(BigDecimal.ZERO) > 0) {
                        //memberWallet1.setBalance(BigDecimalUtils.add(memberWallet1.getBalance(), reward));
                        memberWalletService.increaseBalance(memberWallet1.getId(), reward);
                        MemberTransaction memberTransaction = new MemberTransaction();
                        memberTransaction.setAmount(reward1);
                        memberTransaction.setFee(BigDecimal.ZERO);
                        memberTransaction.setMemberId(member2.getId());
                        memberTransaction.setSymbol(incomeSymbol);
                        memberTransaction.setType(TransactionType.PROMOTION_AWARD);
                        transactionService.save(memberTransaction);

                        RewardRecord rewardRecord1 = new RewardRecord();
                        rewardRecord1.setAmount(reward1);
                        rewardRecord1.setCoin(memberWallet1.getCoin());
                        rewardRecord1.setMember(member2);
                        rewardRecord1.setRemark(rewardPromotionSetting.getType().getCnName());
                        rewardRecord1.setType(RewardRecordType.PROMOTION);
                        rewardRecordService.save(rewardRecord1);
                    }
                }
            }
        }
    }

    /**
     * ??????????????????????????????
     *
     * @param symbol ???????????????
     * @return
     */
    public List<ExchangeOrder> findAllTradingOrderBySymbol(String symbol) {
        Criteria<ExchangeOrder> specification = new Criteria<ExchangeOrder>();
        specification.add(Restrictions.eq("symbol", symbol, false));
        specification.add(Restrictions.eq("status", ExchangeOrderStatus.TRADING, false));
        return exchangeOrderRepository.findAll(specification);
    }

    @Override
    public List<ExchangeOrder> findAll() {
        return exchangeOrderRepository.findAll();
    }

    public ExchangeOrder findOne(String id) {
        return exchangeOrderRepository.findOne(id);
    }

    @Transactional(readOnly = true)
    public PageResult<ExchangeOrder> queryWhereOrPage(List<Predicate> predicates, Integer pageNo, Integer pageSize) {
        List<ExchangeOrder> list;
        JPAQuery<ExchangeOrder> jpaQuery = queryFactory.selectFrom(QExchangeOrder.exchangeOrder);
        if (predicates != null) {
            jpaQuery.where(predicates.toArray(new BooleanExpression[predicates.size()]));
        }
        jpaQuery.orderBy(QExchangeOrder.exchangeOrder.time.desc());
        if (pageNo != null && pageSize != null) {
            list = jpaQuery.offset((pageNo - 1) * pageSize).limit(pageSize).fetch();
        } else {
            list = jpaQuery.fetch();
        }
        PageResult<ExchangeOrder> result = new PageResult<>(list, jpaQuery.fetchCount());
        result.setNumber(pageNo);
        result.setSize(pageSize);
        return result;
    }

    /**
     * ??????????????????
     *
     * @param orderId
     * @return
     */
    @Transactional
    public MessageResult tradeCompleted(String orderId, BigDecimal tradedAmount, BigDecimal turnover) {
        ExchangeOrder order = exchangeOrderRepository.findByOrderId(orderId);
        if (order.getStatus() != ExchangeOrderStatus.TRADING) {
            return MessageResult.error(500, "invalid order(" + orderId + "),not trading status");
        }
        order.setTradedAmount(tradedAmount);
        order.setTurnover(turnover);
        order.setStatus(ExchangeOrderStatus.COMPLETED);
        order.setCompletedTime(Calendar.getInstance().getTimeInMillis());
        exchangeOrderRepository.saveAndFlush(order);

        //??????????????????,??????????????????????????????????????????
        orderRefund(order, tradedAmount, turnover);

        return MessageResult.success("tradeCompleted success");
    }

    /**
     * ?????????????????????????????????????????????????????????
     *
     * @param order
     * @param tradedAmount
     * @param turnover
     */
    public void orderRefund(ExchangeOrder order, BigDecimal tradedAmount, BigDecimal turnover) {
        //???????????????????????????????????????????????????
        BigDecimal frozenBalance, dealBalance;
        if (order.getDirection() == ExchangeOrderDirection.BUY) {
            if (order.getType() == ExchangeOrderType.LIMIT_PRICE) {
                frozenBalance = order.getAmount().multiply(order.getPrice());
            } else {
                frozenBalance = order.getAmount();
            }
            dealBalance = turnover;
        } else {
            frozenBalance = order.getAmount();
            dealBalance = tradedAmount;
        }
        String coinSymbol = order.getDirection() == ExchangeOrderDirection.BUY ? order.getBaseSymbol() : order.getCoinSymbol();
        MemberWallet wallet = memberWalletService.findByCoinUnitAndMemberId(coinSymbol, order.getMemberId());

        //???????????????????????????

        BigDecimal refundAmount = frozenBalance.subtract(dealBalance);
        System.out.println("?????????" + refundAmount);
        log.info("===cancel==?????????" + refundAmount);
        if (refundAmount.compareTo(BigDecimal.ZERO) > 0) {
            memberWalletService.thawBalance(wallet, refundAmount);
        }
    }

    /**
     * ????????????
     *
     * @param orderId ????????????
     * @return
     */
    @Transactional
    public MessageResult cancelOrder(String orderId, BigDecimal tradedAmount, BigDecimal turnover) {
        ExchangeOrder order = findOne(orderId);
        if (order == null) {
            return MessageResult.error("order not exists");
        }
        if (order.getStatus() != ExchangeOrderStatus.TRADING) {
            return MessageResult.error(500, "order not in trading");
        }
        order.setTradedAmount(tradedAmount);
        order.setTurnover(turnover);
        order.setStatus(ExchangeOrderStatus.CANCELED);
        order.setCanceledTime(Calendar.getInstance().getTimeInMillis());
        //??????????????????
        orderRefund(order, tradedAmount, turnover);
        return MessageResult.success();
    }


    /**
     * ???????????????????????????????????????
     *
     * @param uid
     * @param symbol
     * @return
     */
    public long findTodayOrderCancelTimes(Long uid, String symbol) {
        Criteria<ExchangeOrder> specification = new Criteria<ExchangeOrder>();
        specification.add(Restrictions.eq("symbol", symbol, false));
        specification.add(Restrictions.eq("memberId", uid, false));
        specification.add(Restrictions.eq("status", ExchangeOrderStatus.CANCELED, false));
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        long startTick = calendar.getTimeInMillis();
        calendar.add(Calendar.HOUR_OF_DAY, 24);
        long endTick = calendar.getTimeInMillis();
        specification.add(Restrictions.gte("canceledTime", startTick, false));
        specification.add(Restrictions.lt("canceledTime", endTick, false));
        return exchangeOrderRepository.count(specification);
    }

    /**
     * ???????????????????????????????????????
     *
     * @param uid
     * @param symbol
     * @return
     */
    public long findCurrentTradingCount(Long uid, String symbol) {
        Criteria<ExchangeOrder> specification = new Criteria<ExchangeOrder>();
        specification.add(Restrictions.eq("symbol", symbol, false));
        specification.add(Restrictions.eq("memberId", uid, false));
        specification.add(Restrictions.eq("status", ExchangeOrderStatus.TRADING, false));
        return exchangeOrderRepository.count(specification);
    }

    public long findCurrentTradingCount(Long uid, String symbol, ExchangeOrderDirection direction) {
        Criteria<ExchangeOrder> specification = new Criteria<ExchangeOrder>();
        specification.add(Restrictions.eq("symbol", symbol, false));
        specification.add(Restrictions.eq("memberId", uid, false));
        specification.add(Restrictions.eq("direction", direction, false));
        specification.add(Restrictions.eq("status", ExchangeOrderStatus.TRADING, false));
        return exchangeOrderRepository.count(specification);
    }

    public List<ExchangeOrder> findOvertimeOrder(String symbol, int maxTradingTime) {
        Criteria<ExchangeOrder> specification = new Criteria<ExchangeOrder>();
        specification.add(Restrictions.eq("status", ExchangeOrderStatus.TRADING, false));
        specification.add(Restrictions.eq("symbol", symbol, false));
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.SECOND, -maxTradingTime);
        long tickTime = calendar.getTimeInMillis();
        specification.add(Restrictions.lt("time", tickTime, false));
        return exchangeOrderRepository.findAll(specification);
    }

    /**
     * ???????????????????????????
     *
     * @param cancelTime
     * @return
     */
    public List<ExchangeOrder> queryExchangeOrderByTime(long cancelTime) {
        return exchangeOrderRepository.queryExchangeOrderByTime(cancelTime);
    }

    public List<ExchangeOrder> queryExchangeOrderByTimeById(long cancelTime, long sellMemberId, long buyMemberId) {
        return exchangeOrderRepository.queryExchangeOrderByTimeById(cancelTime, sellMemberId, buyMemberId);
    }

    /**
     * API ??????????????????
     *
     * @param memberId
     * @param order
     * @return
     */
    @Transactional
    public String addOrderForApi(Long memberId, ExchangeOrder order) {
        order.setTime(Calendar.getInstance().getTimeInMillis());
        order.setStatus(ExchangeOrderStatus.TRADING);
        order.setTradedAmount(BigDecimal.ZERO);
        order.setOrderId(GeneratorUtil.getOrderId("E"));
//        log.info("add order:{}", order);
        if (order.getDirection() == ExchangeOrderDirection.BUY) {
            MemberWallet wallet = memberWalletService.findByCoinUnitAndMemberId(order.getBaseSymbol(), memberId);
            BigDecimal turnover;
            if (order.getType() == ExchangeOrderType.MARKET_PRICE) {
                turnover = order.getAmount();
            } else {
                turnover = order.getAmount().multiply(order.getPrice());
            }
            if (wallet.getBalance().compareTo(turnover) < 0) {
                return null;
            } else {
                memberWalletService.freezeBalance(wallet, turnover);
                //wallet.setBalance(wallet.getBalance().subtract(turnover));
                //wallet.setFrozenBalance(wallet.getFrozenBalance().add(turnover));
            }
        } else if (order.getDirection() == ExchangeOrderDirection.SELL) {
            MemberWallet wallet = memberWalletService.findByCoinUnitAndMemberId(order.getCoinSymbol(), memberId);
            if (wallet.getBalance().compareTo(order.getAmount()) < 0) {
                return null;
            } else {
                memberWalletService.freezeBalance(wallet, order.getAmount());
                //wallet.setBalance(wallet.getBalance().subtract(order.getAmount()));
                //wallet.setFrozenBalance(wallet.getFrozenBalance().add(order.getAmount()));
            }
        }
        order = exchangeOrderRepository.saveAndFlush(order);
        return order.getOrderId();
    }

    /**
     * Api ??????????????????
     *
     * @param memberId
     * @param symbol
     * @param direction
     * @return
     */
    public Page<ExchangeOrder> findCurrentTradingOrderForApi(long memberId, String symbol, ExchangeOrderDirection direction, int pageNo, int pageSize) {
        Sort orders = new Sort(new Sort.Order(Sort.Direction.DESC, "time"));
        PageRequest pageRequest = new PageRequest(pageNo, pageSize, orders);
        Criteria<ExchangeOrder> specification = new Criteria<ExchangeOrder>();
        specification.add(Restrictions.eq("symbol", symbol, false));
        specification.add(Restrictions.eq("memberId", memberId, false));
        specification.add(Restrictions.eq("status", ExchangeOrderStatus.TRADING, false));
        specification.add(Restrictions.eq("direction", direction, false));
        return exchangeOrderRepository.findAll(specification, pageRequest);
    }


    /**
     * ??????????????????,????????????????????????????????????????????????????????????
     *
     * @param order
     */
    @Transactional
    public void forceCancelOrder(ExchangeOrder order) {
        List<ExchangeOrderDetail> details = exchangeOrderDetailService.findAllByOrderId(order.getOrderId());
        BigDecimal tradedAmount = BigDecimal.ZERO;
        BigDecimal turnover = BigDecimal.ZERO;
        for (ExchangeOrderDetail trade : details) {
            tradedAmount = tradedAmount.add(trade.getAmount());
            turnover = turnover.add(trade.getAmount().multiply(trade.getPrice()));
        }
        order.setTradedAmount(tradedAmount);
        order.setTurnover(turnover);
        if (order.isCompleted()) {
            tradeCompleted(order.getOrderId(), order.getTradedAmount(), order.getTurnover());
        } else {
            cancelOrder(order.getOrderId(), order.getTradedAmount(), order.getTurnover());
        }
    }
}
