package com.bizzan.bitrade;

import com.bizzan.bitrade.service.MemberLevelService;
import com.bizzan.bitrade.service.MemberService;
import com.bizzan.bitrade.service.MiningOrderService;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

@RunWith(SpringRunner.class)
//启动Spring
@SpringBootTest
public class MemberTest {

    @Autowired
    private MemberService memberService;

    @Autowired
    private MemberLevelService memberLevelService;

    @Autowired
    private MiningOrderService miningOrderService;


}
