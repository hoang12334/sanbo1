package com.bizzan.bc.wallet.controller;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.wallet.UnreadableWalletException;
import org.bitcoinj.wallet.Wallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.bizzan.bc.wallet.service.AccountService;
import com.bizzan.bc.wallet.util.HttpClientUtil;
import com.bizzan.bc.wallet.util.MessageResult;

@RestController
@RequestMapping("/rpc")
public class WalletController {
	
	@Value("${bizzan.wallet}")
	private String walletPath;
	
	@Value("${bizzan.blockApi}")
	private String blockApi;
	
	@Autowired
    private AccountService accountService;
	
	private Logger logger = LoggerFactory.getLogger(WalletController.class);
	
	@GetMapping("address/{account}")
    public MessageResult getNewAddress(@PathVariable String account){
        logger.info("create new address: "+account);
        
        NetworkParameters params  = MainNetParams.get(); 
        
        final File walletFile = new File(walletPath);
        
        Wallet wallet = null;
		try {
			wallet = Wallet.loadFromFile(walletFile);
		} catch (UnreadableWalletException e) {
			e.printStackTrace();
			return MessageResult.error(500,"error:" + e.getMessage());
		}
        
        ECKey key = new ECKey();
        
        Address address = key.toAddress(params);
        
        wallet.importKey(key);
        
        try {
			wallet.saveToFile(walletFile);
			accountService.saveOne(account, address.toBase58());
			MessageResult result = new MessageResult(0,"success");
	        result.setData(address.toBase58());
	        
	        return result;
		} catch (IOException e) {
			e.printStackTrace();
			return MessageResult.error(500,"error:" + e.getMessage());
		}
    }
	
	/**
	 * ????????????????????????????????????????????????0?????????????????????????????????????????????????????????
	 * @return
	 */
	@GetMapping("balance")
    public MessageResult balance(){
		MessageResult result = new MessageResult(0,"success");
        result.setData(0);
        return result;
    }
	
	/**
	 * ??????????????????
	 * @param address
	 * @return
	 */
	@GetMapping("balance/{address}")
    public MessageResult balance(@PathVariable String address){
        try {
        	String retStr = HttpClientUtil.doHttpsGet(blockApi + "address/" + address, null, null);
        	if(!StringUtils.isEmpty(retStr)){
        		JSONObject json = JSON.parseObject(retStr);
        		if(json.getIntValue("err_no") == 0) {
        			BigDecimal balance = json.getJSONObject("data").getBigDecimal("balance");
        			if(balance.compareTo(BigDecimal.ZERO) > 0) {
        				balance = balance.divide(BigDecimal.valueOf(1000000));
        			}
        			MessageResult result = new MessageResult(0,"success");
                    result.setData(balance);
                    return result;
        		}
        	}
        	return MessageResult.error(500,"??????????????????");
        }
        catch (Exception e){
            e.printStackTrace();
            return MessageResult.error(500,"error:"+e.getMessage());
        }
    }
	
	@GetMapping("height")
    public MessageResult getHeight(){
        try {
        	String retStr = HttpClientUtil.doHttpsGet(blockApi + "block/latest", null, null);
        	if(!StringUtils.isEmpty(retStr)){
        		JSONObject json = JSON.parseObject(retStr);
        		if(json.getIntValue("err_no") == 0) {
        			Long height = json.getJSONObject("data").getLong("height");
        			MessageResult result = new MessageResult(0,"success");
                    result.setData(height);
                    return result;
        		}
        	}
        	return MessageResult.error(500,"??????????????????");
        }
        catch (Exception e){
            e.printStackTrace();
            return MessageResult.error(500,"????????????,error:"+e.getMessage());
        }
    }
	
	/**
	 * TODO ??????????????????
	 * @param address
	 * @param amount
	 * @param fee
	 * @return
	 */
	@GetMapping({"transfer","withdraw"})
    public MessageResult withdraw(String address, BigDecimal amount,BigDecimal fee){
		return MessageResult.error(500, "?????????????????????");
    }
}
