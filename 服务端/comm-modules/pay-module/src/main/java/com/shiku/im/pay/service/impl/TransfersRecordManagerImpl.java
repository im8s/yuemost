package com.shiku.im.pay.service.impl;

import com.alipay.api.AlipayApiException;
import com.alipay.api.request.AlipayFundTransToaccountTransferRequest;
import com.alipay.api.response.AlipayFundTransToaccountTransferResponse;
import com.alipay.util.AliPayUtil;
import com.shiku.im.comm.constants.KConstants;
import com.shiku.im.comm.utils.NumberUtil;
import com.shiku.im.comm.utils.ReqUtil;
import com.shiku.im.pay.constants.PayResultCode;
import com.shiku.im.pay.dao.AliPayTransfersRecordDao;
import com.shiku.im.pay.dao.ConsumeRecordDao;
import com.shiku.im.pay.dao.TransfersRecordDao;
import com.shiku.im.pay.entity.AliPayTransfersRecord;
import com.shiku.im.pay.entity.BaseConsumeRecord;
import com.shiku.im.pay.entity.TransfersRecord;
import com.shiku.im.user.config.WXConfig;
import com.shiku.im.user.constants.MoneyLogConstants.MoenyAddEnum;
import com.shiku.im.user.constants.MoneyLogConstants.MoneyLogEnum;
import com.shiku.im.user.constants.MoneyLogConstants.MoneyLogTypeEnum;
import com.shiku.im.user.entity.User;
import com.shiku.im.user.entity.UserMoneyLog;
import com.shiku.im.user.service.UserCoreRedisRepository;
import com.shiku.im.user.service.UserCoreService;
import com.shiku.im.utils.SKBeanUtils;
import com.shiku.im.vo.JSONMessage;
import com.shiku.utils.CollectionUtil;
import com.shiku.utils.DateUtil;
import com.shiku.utils.StringUtil;
import com.wxpay.utils.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Map;

@Service
public class TransfersRecordManagerImpl {

	@Autowired
	private TransfersRecordDao transfersRecordDao;
	public TransfersRecordDao getTransfersRecordDao(){
		return transfersRecordDao;
	}
	@Autowired
	private ConsumeRecordDao consumeRecordDao;
	@Autowired
	private AliPayTransfersRecordDao aliPayTransfersRecordDao;


	private static final String TRANSFERS_PAY = "https://api.mch.weixin.qq.com/mmpaymkttransfers/promotion/transfers"; // ????????????

	protected Logger logger= LoggerFactory.getLogger(this.getClass());


	@Autowired
	private UserCoreService userCoreService;

	/*@Autowired
	private AuthKeysService authKeysService;*/

	@Autowired
	private ConsumeRecordManagerImpl consumeRecordManager;

	@Autowired
	private UserCoreRedisRepository userCoreRedisRepository;

	@Autowired(required = false)
	private WXConfig wxConfig;


	/**
	 * ????????? ???????????? Key
	 */
	private static final String transferLockKey="transferLock:%s";


	/**
	 * ????????????
	 * ???????????????
	 * @param record
	 */
	private  void  transfersToWXUser(TransfersRecord record) {
		try {
			BaseConsumeRecord entity=new BaseConsumeRecord();
			entity.setTime(DateUtil.currentTimeSeconds());
			entity.setType(KConstants.ConsumeType.PUT_RAISE_CASH);
			entity.setChangeType(KConstants.MOENY_REDUCE);
			entity.setDesc("????????????");
			entity.setStatus(KConstants.OrderStatus.END);
			entity.setTradeNo(record.getOutTradeNo());
			entity.setPayType(KConstants.PayType.BALANCEAY);

			
			DecimalFormat df = new DecimalFormat("#.00");
			double total=Double.valueOf(record.getTotalFee());
			
			total= Double.valueOf(df.format(total));
			
			entity.setMoney(total);

			UserMoneyLog userMoneyLog =new UserMoneyLog(record.getUserId(),0,record.getOutTradeNo(),total,
					MoenyAddEnum.MOENY_REDUCE, MoneyLogEnum.CASH_OUT, MoneyLogTypeEnum.NORMAL_PAY);

			userMoneyLog.setExtra("WX_PAY");


			Double balance = userCoreService.rechargeUserMoenyV1(userMoneyLog);

			entity.setServiceCharge(Double.valueOf(record.getFee()));// ?????????
			entity.setOperationAmount(Double.valueOf(record.getRealFee()));// ??????????????????
			entity.setCurrentBalance(balance);// ????????????
			consumeRecordManager.saveConsumeRecord(entity);
			transfersRecordDao.addTransfersRecord(record);

		} catch (Exception e) {
			e.printStackTrace();
		}
		
		
	}
	
	/**
	 * ???????????????
	 * @param record
	 */
	private  void transfersToAliPay(AliPayTransfersRecord record){
		try {

			double total=Double.valueOf(record.getTotalFee());

			UserMoneyLog userMoneyLog =new UserMoneyLog(record.getUserId(),0,record.getOutTradeNo(),total,
					MoenyAddEnum.MOENY_REDUCE, MoneyLogEnum.CASH_OUT, MoneyLogTypeEnum.NORMAL_PAY);

			userMoneyLog.setExtra("ALI_PAY");


			Double balance =userCoreService.rechargeUserMoenyV1(userMoneyLog);

			BaseConsumeRecord entity=new BaseConsumeRecord();
			entity.setUserId(ReqUtil.getUserId());
			entity.setTime(DateUtil.currentTimeSeconds());
			entity.setType(KConstants.ConsumeType.PUT_RAISE_CASH);
			entity.setChangeType(KConstants.MOENY_REDUCE);
			entity.setDesc("???????????????");
			entity.setStatus(KConstants.OrderStatus.END);
			entity.setTradeNo(record.getOutTradeNo());
			entity.setPayType(KConstants.PayType.BALANCEAY);

			entity.setMoney(total);
			entity.setServiceCharge(Double.valueOf(record.getFee()));// ?????????
			entity.setOperationAmount(Double.valueOf(record.getRealFee()));// ??????????????????
			entity.setBusinessId(record.getOutTradeNo());
			// ????????????
			entity.setCurrentBalance(balance);
			aliPayTransfersRecordDao.addAliPayTransfersRecord(record);
			consumeRecordDao.addConsumRecord(entity);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}



	public JSONMessage wxWithdrawalPay(String amount, User user, String remoteAddr) {
		int userId=user.getUserId();
		String openid=user.getOpenid();
		/**
		 * ???????????? 0.3???
		 */
		//amount="30";



		DecimalFormat df = new DecimalFormat("#.00");
		/**
		 * 0.5
		 * ????????????
		 */
		double total=Double.valueOf(amount);

		if(0.5>total) {
			return JSONMessage.failureByErrCode(PayResultCode.WithdrawMin);
		}
		/**
		 * 0.01
		 *
		 * 0.6%
		 * ???????????????
		 * 2020-01-07 12:00 ????????????????????????????????????
		 */
		double fee =Double.valueOf(df.format((total*SKBeanUtils.getImCoreService().getPayConfig().getMyChangeWithdrawRate())));
		if(0.01>fee) {
			fee=0.01;
		}else  {
			fee= NumberUtil.getCeil(fee, 2);
		}

		/**
		 * 0.49
		 * ??????????????????
		 */
		Double totalFee= Double.valueOf(df.format(total-fee));
		/**
		 * ?????? ???????????? ??????????????????
		 */
		try {
			final double feel = fee;
			return (JSONMessage) userCoreService.payMoenyBalanceOnLock(userId,total, callback -> {
				return transfersToWXOnLock(user,total,totalFee,feel,remoteAddr);
			});

		} catch (Exception e) {
			logger.error(e.getMessage(),e);
		}

		return JSONMessage.failureByErrCode(PayResultCode.WithdrawFailure);


	}

	/**
	 * ?????? ????????? ?????????
	 * @param user
	 * @param total
	 * @param totalFee
	 * @param fee
	 * @param remoteAddr
	 * @return
	 */
	private JSONMessage transfersToWXOnLock(User user,double total,double totalFee,double fee,String remoteAddr){

		int userId=user.getUserId();
		Double balance = userCoreService.getUserMoenyV1(userId);
		if(totalFee>balance) {
			return JSONMessage.failureByErrCode(PayResultCode.InsufficientBalance);
		}

		/**
		 * 49.0
		 */
		Double realFee=(totalFee*100);

		/**
		 * 49
		 */
		String realFeeStr=realFee.intValue()+"";

		logger.info(String.format("=== transferPay userid %s username %s ????????????   %s ?????????   %s  ????????????   %s ",
				userId,user.getNickname(),total,fee,totalFee));
		/**
		 * ow9Ctwy_qP8OoLr_6T-5oMnBud8w
		 */




		Map<String, String> restmap = null;

		TransfersRecord record=new TransfersRecord();
		try {
			record.setUserId(userId);
			record.setAppid(wxConfig.getAppid());
			record.setMchId(wxConfig.getMchid());
			record.setNonceStr(WXPayUtil.getNonceStr());
			record.setOutTradeNo(StringUtil.getOutTradeNo());
			record.setOpenid(user.getOpenid());
			record.setTotalFee(String.valueOf(total));
			record.setFee(fee+"");
			record.setRealFee(totalFee+"");
			record.setCreateTime(DateUtil.currentTimeSeconds());
			record.setStatus(0);

			Map<String, String> parm = new HashMap<String, String>();
			parm.put("mch_appid", wxConfig.getAppid()); //????????????appid
			parm.put("mchid", wxConfig.getMchid()); //?????????
			parm.put("nonce_str", record.getNonceStr()); //???????????????
			parm.put("partner_trade_no", record.getOutTradeNo()); //???????????????
			parm.put("openid", user.getOpenid()); //??????openid
			parm.put("check_name", "NO_CHECK"); //???????????????????????? OPTION_CHECK
			//parm.put("re_user_name", "??????"); //check_name?????????FORCE_CHECK???OPTION_CHECK????????????
			parm.put("amount", realFeeStr); //????????????
			parm.put("desc", "??????????????????"); //????????????????????????
			parm.put("spbill_create_ip", remoteAddr); //??????Ip??????
			parm.put("sign", PayUtil.getSign(parm, wxConfig.getApiKey()));

			String restxml = HttpUtils.posts(TRANSFERS_PAY, XmlUtil.xmlFormat(parm, false));
			restmap = WXNotify.parseXmlToList2(restxml);
		} catch (Exception e) {
			logger.error(e.getMessage());
		}

		if (CollectionUtil.isNotEmpty(restmap) && "SUCCESS".equals(restmap.get("result_code"))) {
			logger.info("???????????????" + restmap.get("result_code") + ":" + restmap.get("return_code"));
			Map<String, String> transferMap = new HashMap<>();
			transferMap.put("partner_trade_no", restmap.get("partner_trade_no"));//?????????????????????
			transferMap.put("payment_no", restmap.get("payment_no")); //???????????????
			transferMap.put("payment_time", restmap.get("payment_time")); //????????????????????????

			record.setPayNo(restmap.get("payment_no"));
			record.setPayTime(restmap.get("payment_time"));
			record.setResultCode(restmap.get("result_code"));
			record.setReturnCode(restmap.get("return_code"));
			record.setStatus(1);
			record.setUserId(ReqUtil.getUserId());
			transfersToWXUser(record);

			return JSONMessage.success(transferMap);
		}else {
			if (CollectionUtil.isNotEmpty(restmap)) {
				String resultMsg=restmap.get("err_code") + ":" + restmap.get("err_code_des");
				logger.error("??????????????? ?????????????????? " + resultMsg);
				record.setErrCode(restmap.get("err_code"));
				record.setErrDes(restmap.get("err_code_des"));
				record.setStatus(-1);
				transfersRecordDao.addTransfersRecord(record);
				return JSONMessage.failure(resultMsg);
			}
			return JSONMessage.failureByErrCode(PayResultCode.WithdrawFailure);
		}
	}

	private JSONMessage transfersToAliPayOnLock(User user,String amount,double totalFee,double fee){
		int userId=user.getUserId();
		Double balance = userCoreService.getUserMoenyV1(userId);
		if(totalFee>balance) {
			return JSONMessage.failureByErrCode(PayResultCode.InsufficientBalance);
		}
		String orderId=StringUtil.getOutTradeNo();
		AliPayTransfersRecord record=new AliPayTransfersRecord();
		record.setUserId(userId);
		record.setAppid(AliPayUtil.APP_ID);
		record.setOutTradeNo(orderId);
		record.setAliUserId(user.getAliUserId());
		record.setTotalFee(amount);
		record.setFee(fee+"");
		record.setRealFee(totalFee+"");
		record.setCreateTime(DateUtil.currentTimeSeconds());
		record.setStatus(0);

		AlipayFundTransToaccountTransferRequest request = new AlipayFundTransToaccountTransferRequest();
//		request.setBizModel(bizModel);

		request.setBizContent("{" +
				"    \"out_biz_no\":\""+orderId+"\"," +  // ??????Id
				"    \"payee_type\":\"ALIPAY_USERID\"," + // ????????????????????????
				"    \"payee_account\":\""+user.getAliUserId()+"\"," + // ?????????
				"    \"amount\":\""+totalFee+"\"," +	// ??????
				"    \"payer_show_name\":\"????????????\"," +
				"    \"remark\":\"????????????\"," +
				"  }");
		try {
			AlipayFundTransToaccountTransferResponse response = AliPayUtil.getAliPayClient().execute(request);
			System.out.println("??????????????????  "+response.getCode());
			if(response.isSuccess()){
				record.setResultCode(response.getCode());
				record.setCreateTime(DateUtil.toTimestamp(response.getPayDate()));
				record.setStatus(1);
				transfersToAliPay(record);

				logger.info("?????????????????????");
				return JSONMessage.success();
			} else {
				record.setErrCode(response.getErrorCode());
				record.setErrDes(response.getMsg());
				record.setStatus(-1);
				aliPayTransfersRecordDao.addAliPayTransfersRecord(record);
				logger.info("?????????????????????");
				return JSONMessage.failureByErrCode(PayResultCode.WithdrawFailure);
			}
		} catch (AlipayApiException e) {
			e.printStackTrace();
			return JSONMessage.failureByErrCode(PayResultCode.WithdrawFailure);
		}
	}

	public  JSONMessage aliWithdrawalPay(User user, String amount) {
		int userId=user.getUserId();
		// ????????????
		double total=(Double.valueOf(amount));


		/**
		 * ??????????????? 0.6%
		 * ?????????????????????????????????????????????????????????0.6%??????????????????????????????0.6%?????????
		 * 2020-01-07 12:00 ????????????????????????????????????
		 */
		DecimalFormat df = new DecimalFormat("#.00");
		double fee =Double.valueOf(df.format(total* SKBeanUtils.getImCoreService().getPayConfig().getMyChangeWithdrawRate()));
		if(0.01>fee) {
			fee=0.01;
		}else  {
			fee=NumberUtil.getCeil(fee, 2);
		}

		/**
		 *
		 * ??????????????????  = ????????????-?????????
		 */
		Double totalFee= Double.valueOf(df.format(total-fee));

		/**
		 * ?????? ???????????? ??????????????????
		 */

		try {
			final  double fee1  = fee;
			return (JSONMessage) userCoreService.payMoenyBalanceOnLock(userId,total, callback->{
				return transfersToAliPayOnLock(user,amount,totalFee,fee1);
			});
		} catch (Exception e) {
			logger.error(e.getMessage(),e);
			return JSONMessage.failureByErrCode(PayResultCode.WithdrawFailure);
		}



	}

}
