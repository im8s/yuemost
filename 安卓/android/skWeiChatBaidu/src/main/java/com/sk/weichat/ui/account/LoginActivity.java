package com.sk.weichat.ui.account;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.alibaba.fastjson.JSON;
import com.gunan.im.wxapi.WXEntryActivity;
import com.sk.weichat.AppConfig;
import com.sk.weichat.AppConstant;
import com.sk.weichat.BuildConfig;
import com.sk.weichat.MyApplication;
import com.sk.weichat.R;
import com.sk.weichat.bean.LoginRegisterResult;
import com.sk.weichat.bean.QQLoginResult;
import com.sk.weichat.bean.WXUploadResult;
import com.sk.weichat.bean.event.MessageLogin;
import com.sk.weichat.helper.DialogHelper;
import com.sk.weichat.helper.LoginHelper;
import com.sk.weichat.helper.LoginSecureHelper;
import com.sk.weichat.helper.PasswordHelper;
import com.sk.weichat.helper.PrivacySettingHelper;
import com.sk.weichat.helper.QQHelper;
import com.sk.weichat.helper.UsernameHelper;
import com.sk.weichat.helper.YeepayHelper;
import com.sk.weichat.ui.base.BaseActivity;
import com.sk.weichat.ui.me.SetConfigActivity;
import com.sk.weichat.util.AppUtils;
import com.sk.weichat.util.Constants;
import com.sk.weichat.util.DeviceInfoUtil;
import com.sk.weichat.util.EventBusHelper;
import com.sk.weichat.util.PermissionUtil;
import com.sk.weichat.util.PreferenceUtils;
import com.sk.weichat.util.ToastUtil;
import com.sk.weichat.util.secure.LoginPassword;
import com.sk.weichat.view.VerifyDialog;
import com.tencent.tauth.Tencent;
import com.xuan.xuanhttplibrary.okhttp.HttpUtils;
import com.xuan.xuanhttplibrary.okhttp.callback.BaseCallback;
import com.xuan.xuanhttplibrary.okhttp.result.ObjectResult;
import com.xuan.xuanhttplibrary.okhttp.result.Result;

import java.util.HashMap;
import java.util.Map;

import de.greenrobot.event.Subscribe;
import de.greenrobot.event.ThreadMode;
import okhttp3.Call;

/**
 * ????????????
 *
 * @author Dean Tao
 * @version 1.0
 */
public class LoginActivity extends BaseActivity implements View.OnClickListener {
    public static final String THIRD_TYPE_WECHAT = "2";
    public static final String THIRD_TYPE_QQ = "1";

    private EditText mPhoneNumberEdit;
    private EditText mPasswordEdit;
    private TextView tv_prefix;
    private int mobilePrefix = 86;
    private String thirdToken;
    private String thirdTokenType;
    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            finish();
        }
    };
    private Button forgetPasswordBtn, registerBtn, loginBtn;
    private boolean third;
    private VerifyDialog mVerifyDialog;

    public LoginActivity() {
        noLoginRequired();
    }

    public static void bindThird(Context ctx, String thirdToken, String thirdTokenType, boolean testLogin) {
        Intent intent = new Intent(ctx, LoginActivity.class);
        intent.putExtra("thirdToken", thirdToken);
        intent.putExtra("thirdTokenType", thirdTokenType);
        intent.putExtra("testLogin", testLogin);
        ctx.startActivity(intent);
    }

    public static void bindThird(Context ctx, String thirdToken, String thirdTokenType) {
        bindThird(ctx, thirdToken, thirdTokenType, false);
    }

    public static void bindThird(Context ctx, WXUploadResult thirdToken) {
        bindThird(ctx, JSON.toJSONString(thirdToken), THIRD_TYPE_WECHAT, true);
    }

    public static void bindThird(Context ctx, QQLoginResult thirdToken) {
        bindThird(ctx, JSON.toJSONString(thirdToken), THIRD_TYPE_QQ, true);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        PermissionUtil.requestLocationPermissions(this, 0x01);

        thirdToken = getIntent().getStringExtra("thirdToken");
        thirdTokenType = getIntent().getStringExtra("thirdTokenType");
        initActionBar();
        initView();

        IntentFilter filter = new IntentFilter();
        filter.addAction("CHANGE_CONFIG");
        registerReceiver(broadcastReceiver, filter);

        if (!TextUtils.isEmpty(thirdToken) && getIntent().getBooleanExtra("testLogin", false)) {
            // ??????????????????????????????
            // ?????????????????????????????????????????????
            mPhoneNumberEdit.setText("");
            login(true);
        }
        EventBusHelper.register(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // ????????????????????????????????????????????????????????????????????????
        if (!MyApplication.getInstance().getBdLocationHelper().isLocationUpdate()) {
            MyApplication.getInstance().getBdLocationHelper().requestLocation();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(broadcastReceiver);
    }

    private void initActionBar() {
        getSupportActionBar().hide();
        findViewById(R.id.iv_title_left).setVisibility(View.GONE);
        TextView tvTitle = (TextView) findViewById(R.id.tv_title_center);
        if (TextUtils.isEmpty(thirdToken)) {
            tvTitle.setText(getString(R.string.login));
        } else {
            // ??????????????????????????????????????????????????????????????????
            tvTitle.setText(getString(R.string.bind_old_account));
        }
        TextView tvRight = (TextView) findViewById(R.id.tv_title_right);
        // ???????????????????????????????????????
        if (!AppConfig.isShiku() || !BuildConfig.DEBUG) {
            // ???????????????????????????????????????adb shell????????????"setprop log.tag.ShikuServer D"?????????
            if (!Log.isLoggable("ShikuServer", Log.DEBUG)) {
                tvRight.setVisibility(View.GONE);
            }
        }
        // ???????????????????????????????????????
        tvTitle.setOnLongClickListener(v -> {
            tvRight.setVisibility(View.VISIBLE);
            return false;
        });
        tvRight.setText(R.string.settings_server_address);
        tvRight.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(LoginActivity.this, SetConfigActivity.class);
                startActivity(intent);
            }
        });
    }

    private void initView() {
        mPhoneNumberEdit = (EditText) findViewById(R.id.phone_numer_edit);
        mPasswordEdit = (EditText) findViewById(R.id.password_edit);
        PasswordHelper.bindPasswordEye(mPasswordEdit, findViewById(R.id.tbEye));
        tv_prefix = (TextView) findViewById(R.id.tv_prefix);
        if (coreManager.getConfig().registerUsername) {
            tv_prefix.setVisibility(View.GONE);
        } else {
            tv_prefix.setOnClickListener(this);
        }
        mobilePrefix = PreferenceUtils.getInt(this, Constants.AREA_CODE_KEY, mobilePrefix);
        tv_prefix.setText("+" + mobilePrefix);

        // ????????????
        loginBtn = (Button) findViewById(R.id.login_btn);
        loginBtn.setOnClickListener(this);
        // ????????????
        registerBtn = (Button) findViewById(R.id.register_account_btn);
        if (coreManager.getConfig().isOpenRegister) {
            if (TextUtils.isEmpty(thirdToken)) {
                registerBtn.setOnClickListener(this);
            } else {
                // ??????????????????????????????????????????????????????????????????????????????????????????
                registerBtn.setVisibility(View.GONE);
            }
        } else {
            registerBtn.setVisibility(View.GONE);
        }
        // ????????????
        forgetPasswordBtn = (Button) findViewById(R.id.forget_password_btn);
        if (!TextUtils.isEmpty(thirdToken) || coreManager.getConfig().registerUsername) {
            forgetPasswordBtn.setVisibility(View.GONE);
        }
        forgetPasswordBtn.setOnClickListener(this);
        UsernameHelper.initEditText(mPhoneNumberEdit, coreManager.getConfig().registerUsername);
        loginBtn.setText(getString(R.string.login));
        registerBtn.setText(getString(R.string.register));
        forgetPasswordBtn.setText(getString(R.string.forget_password));

        findViewById(R.id.sms_login_btn).setOnClickListener(this);

        if (TextUtils.isEmpty(thirdToken)) {
            findViewById(R.id.wx_login_btn).setOnClickListener(this);
            if (QQHelper.ENABLE) {
                findViewById(R.id.qq_login_btn).setOnClickListener(this);
            } else {
                findViewById(R.id.qq_login_fl).setVisibility(View.GONE);
            }
        } else {
//            findViewById(R.id.wx_login_fl).setVisibility(View.GONE);
            findViewById(R.id.qq_login_fl).setVisibility(View.GONE);
        }

        findViewById(R.id.main_content).setOnClickListener(this);

        if (!coreManager.getConfig().thirdLogin) {
//            findViewById(R.id.wx_login_fl).setVisibility(View.GONE);
            findViewById(R.id.qq_login_fl).setVisibility(View.GONE);
        }

        if (coreManager.getConfig().registerUsername) {
            // ?????????????????????????????????????????????????????????
            findViewById(R.id.sms_login_fl).setVisibility(View.GONE);
            findViewById(R.id.tv_user_name).setVisibility(View.VISIBLE);
        }else {
            findViewById(R.id.tv_user_name).setVisibility(View.GONE);
        }

    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.tv_prefix:
                // ??????????????????
                Intent intent = new Intent(this, SelectPrefixActivity.class);
                startActivityForResult(intent, SelectPrefixActivity.REQUEST_MOBILE_PREFIX_LOGIN);
                break;
            case R.id.login_btn:
                // ??????
                login(false);
                break;
            case R.id.wx_login_btn:
                if (!AppUtils.isAppInstalled(mContext, "com.tencent.mm")) {
                    Toast.makeText(mContext, getString(R.string.tip_no_wx_chat), Toast.LENGTH_SHORT).show();
                } else {
                    WXEntryActivity.wxLogin(this);
                }
                break;
            case R.id.qq_login_btn:
                if (!QQHelper.qqInstalled(mContext)) {
                    Toast.makeText(mContext, getString(R.string.tip_no_qq_chat), Toast.LENGTH_SHORT).show();
                } else {
                    QQHelper.qqLogin(this);
                }
                break;
            case R.id.register_account_btn:
                // ??????
                register();
                break;
            case R.id.forget_password_btn:
                // ????????????
                startActivity(new Intent(mContext, FindPwdActivity.class));
                break;
            case R.id.sms_login_btn:
                startActivity(new Intent(mContext, AuthCodeActivity.class));
                break;
            case R.id.main_content:
                // ?????????????????????????????????
                InputMethodManager inputManager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (inputManager != null) {
                    inputManager.hideSoftInputFromWindow(findViewById(R.id.main_content).getWindowToken(), 0); //??????????????????
                }
                break;
        }
    }

    private void register() {
        RegisterActivity.registerFromThird(
                this,
                mobilePrefix,
                mPhoneNumberEdit.getText().toString(),
                mPasswordEdit.getText().toString(),
                thirdToken,
                thirdTokenType
        );
    }

    /**
     * @param third ????????????????????????
     */
    private void login(boolean third) {
        this.third = third;
        login();
    }

    private void login() {
        PreferenceUtils.putInt(this, Constants.AREA_CODE_KEY, mobilePrefix);
        final String phoneNumber = mPhoneNumberEdit.getText().toString().trim();
        String password = mPasswordEdit.getText().toString().trim();

        if (TextUtils.isEmpty(thirdToken)) {
            // ??????????????????????????????????????????
            if (TextUtils.isEmpty(phoneNumber) && TextUtils.isEmpty(password)) {
                Toast.makeText(mContext, getString(R.string.please_input_account_and_password), Toast.LENGTH_SHORT).show();
                return;
            }

            if (TextUtils.isEmpty(phoneNumber)) {
                Toast.makeText(mContext, getString(R.string.please_input_account), Toast.LENGTH_SHORT).show();
                return;
            }

            if (TextUtils.isEmpty(password)) {
                Toast.makeText(mContext, getString(R.string.input_pass_word), Toast.LENGTH_SHORT).show();
                return;
            }
        }

        // ?????????????????????
        final String digestPwd = LoginPassword.encodeMd5(password);

        DialogHelper.showDefaulteMessageProgressDialog(this);

        Map<String, String> params = new HashMap<>();
        params.put("xmppVersion", "1");
        // ????????????+
        params.put("model", DeviceInfoUtil.getModel());
        params.put("osVersion", DeviceInfoUtil.getOsVersion());
        params.put("serial", DeviceInfoUtil.getDeviceId(mContext));
        // ????????????
        double latitude = MyApplication.getInstance().getBdLocationHelper().getLatitude();
        double longitude = MyApplication.getInstance().getBdLocationHelper().getLongitude();
        if (latitude != 0)
            params.put("latitude", String.valueOf(latitude));
        if (longitude != 0)
            params.put("longitude", String.valueOf(longitude));

        if (MyApplication.IS_OPEN_CLUSTER) {// ?????????????????????
            String area = PreferenceUtils.getString(this, AppConstant.EXTRA_CLUSTER_AREA);
            if (!TextUtils.isEmpty(area)) {
                params.put("area", area);
            }
        }

        LoginSecureHelper.secureLogin(
                this, coreManager, String.valueOf(mobilePrefix), phoneNumber, password, thirdToken, thirdTokenType, third,
                params,
                t -> {
                    DialogHelper.dismissProgressDialog();
                    ToastUtil.showToast(this, this.getString(R.string.tip_login_secure_place_holder, t.getMessage()));
                }, result -> {
                    DialogHelper.dismissProgressDialog();
                    if (!Result.checkSuccess(getApplicationContext(), result)) {
                        if (Result.checkError(result, Result.CODE_THIRD_NO_EXISTS)) {
                            // ????????????1040306????????????IM???????????????????????????????????????????????????IM????????????????????????
                            register();
                        } else if (Result.checkError(result, Result.CODE_THIRD_NO_PHONE)) {
                            // ??????????????????IM?????????????????????????????????????????????????????????????????????
                            register();
                            finish();
                        }
                        return;
                    }
                    if (!TextUtils.isEmpty(result.getData().getAuthKey())) {
                        DialogHelper.showMessageProgressDialog(mContext, getString(R.string.tip_need_auth_login));
                        CheckAuthLoginRunnable authLogin = new CheckAuthLoginRunnable(result.getData().getAuthKey(), phoneNumber, digestPwd);
                        waitAuth(authLogin);
                        return;
                    }
                    afterLogin(result, phoneNumber, digestPwd);
                }
        );
    }

    private void afterLogin(ObjectResult<LoginRegisterResult> result, String phoneNumber, String digestPwd) {
        if (third) {
            if (MyApplication.IS_SUPPORT_SECURE_CHAT
                    && result.getData().getIsSupportSecureChat() == 1) {// ??????????????????????????????????????????????????????????????????
                // SecureFlag
                // ??????/QQ????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
                // ??????/QQ???????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
                mVerifyDialog = new VerifyDialog(mContext);
                mVerifyDialog.setVerifyClickListener(getString(R.string.input_password_to_decrypt_keys), new VerifyDialog.VerifyClickListener() {
                    @Override
                    public void cancel() {
                        mVerifyDialog.dismiss();
                        String sAreaCode = result.getData().getAreaCode();
                        String rTelephone = result.getData().getTelephone();
                        if (!TextUtils.isEmpty(rTelephone)) {
                            if (!TextUtils.isEmpty(sAreaCode) && rTelephone.startsWith(sAreaCode)) {
                                rTelephone = rTelephone.substring(sAreaCode.length());
                            }
                            FindPwdActivity.start(mContext, Integer.valueOf(sAreaCode), rTelephone);
                        } else {
                            startActivity(new Intent(mContext, FindPwdActivity.class));
                        }
                    }

                    @Override
                    public void send(String str) {
                        checkPasswordWXAuthCodeLogin(str, result, phoneNumber, digestPwd);
                    }
                });
                mVerifyDialog.setDismiss(false);
                mVerifyDialog.setCancelButton(R.string.forget_password);
                mVerifyDialog.show();
            } else {
                start(mPasswordEdit.getText().toString().trim(), result, phoneNumber, digestPwd);
            }
        } else {
            start(mPasswordEdit.getText().toString().trim(), result, phoneNumber, digestPwd);
        }
/*
        boolean success = LoginHelper.setLoginUser(mContext, coreManager, phoneNumber, digestPwd, result);
        if (success) {
            // SecureFlag ???????????????????????????????????????????????????
            if (third) {
                if (MyApplication.IS_SUPPORT_SECURE_CHAT
                        && result.getData().getIsSupportSecureChat() == 1) {// ??????????????????????????????????????????????????????????????????
                    // ??????/QQ????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
                    // ??????/QQ???????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
                    mVerifyDialog = new VerifyDialog(mContext);
                    mVerifyDialog.setVerifyClickListener(getString(R.string.input_password_to_decrypt_keys), new VerifyDialog.VerifyClickListener() {
                        @Override
                        public void cancel() {
                            mVerifyDialog.dismiss();
                            startActivity(new Intent(mContext, FindPwdActivity.class));
                        }

                        @Override
                        public void send(String str) {
                            checkPasswordWXAuthCodeLogin(str, result);
                        }
                    });
                    mVerifyDialog.setDismiss(false);
                    mVerifyDialog.setCancelButton(R.string.forget_password);
                    mVerifyDialog.show();
                } else {
                    start("", result);
                }
            } else {
                start(mPasswordEdit.getText().toString().trim(), result);
            }
        } else {
            // ????????????????????????app??????????????????
            // java.sql.SQLException: Unable to run insert stmt on object com.sk.weichat.bean.User@d9c51ec: INSERT INTO
            // `user` (`account` ,`areaId` ,`attCount` ,`birthday` ,`cityId` ,`company_id` ,`countryId` ,`description` ,`fansCount` ,`friendsCount` ,`integral` ,`integralTotal` ,`isAuth` ,`level` ,`money` ,`moneyTotal` ,`msgBackGroundUrl` ,`nickName` ,`offlineTime` ,`password` ,`phone` ,`provinceId` ,`setAccountCount` ,`sex` ,`showLastLoginTime` ,`status` ,`telephone` ,`userId` ,`userType` ,`vip` )
            // VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ToastUtil.showToast(mContext, result.getResultMsg());
        }
*/
    }

    private void waitAuth(CheckAuthLoginRunnable authLogin) {
        authLogin.waitAuthHandler.postDelayed(authLogin, 3000);
    }

    private void checkPasswordWXAuthCodeLogin(String password, ObjectResult<LoginRegisterResult> registerResult,
                                              String extra1, String extra2) {

        LoginHelper.saveUserForThirdSmsVerifyPassword(mContext, coreManager,
                extra1, extra2, registerResult);

        Map<String, String> params = new HashMap<>();
        params.put("access_token", coreManager.getSelfStatus().accessToken);
        params.put("password", LoginPassword.encodeMd5(password));

        DialogHelper.showDefaulteMessageProgressDialog(mContext);

        HttpUtils.get().url(coreManager.getConfig().USER_VERIFY_PASSWORD)
                .params(params)
                .build()
                .execute(new BaseCallback<Void>(Void.class) {
                    @Override
                    public void onResponse(ObjectResult<Void> result) {
                        DialogHelper.dismissProgressDialog();
                        if (Result.checkSuccess(mContext, result)) {
                            mVerifyDialog.dismiss();
                            start(password, registerResult, extra1, extra2);
                        }
                    }

                    @Override
                    public void onError(Call call, Exception e) {
                        DialogHelper.dismissProgressDialog();
                        ToastUtil.showErrorNet(mContext);
                    }
                });
    }

    private void start(String password, ObjectResult<LoginRegisterResult> result, String phoneNumber, String digestPwd) {
        LoginHelper.setLoginUser(mContext, coreManager, phoneNumber, digestPwd, result);

        LoginRegisterResult.Settings settings = result.getData().getSettings();
        MyApplication.getInstance().initPayPassword(result.getData().getUserId(), result.getData().getPayPassword());
        YeepayHelper.saveOpened(mContext, result.getData().getWalletUserNo() == 1);
        PrivacySettingHelper.setPrivacySettings(LoginActivity.this, settings);
        MyApplication.getInstance().initMulti();

        // startActivity(new Intent(mContext, DataDownloadActivity.class));
        DataDownloadActivity.start(mContext, result.getData().getIsupdate(), password);
        finish();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case SelectPrefixActivity.REQUEST_MOBILE_PREFIX_LOGIN:
                if (resultCode != SelectPrefixActivity.RESULT_MOBILE_PREFIX_SUCCESS) {
                    return;
                }
                mobilePrefix = data.getIntExtra(Constants.MOBILE_PREFIX, 86);
                tv_prefix.setText("+" + mobilePrefix);
                break;
            case com.tencent.connect.common.Constants.REQUEST_LOGIN:
            case com.tencent.connect.common.Constants.REQUEST_APPBAR:
                Tencent.onActivityResultData(requestCode, resultCode, data, QQHelper.getLoginListener(mContext));
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MainThread)
    public void helloEventBus(MessageLogin message) {
        finish();
    }

    private class CheckAuthLoginRunnable implements Runnable {
        private final String phoneNumber;
        private final String digestPwd;
        private Handler waitAuthHandler = new Handler();
        private int waitAuthTimes = 10;
        private String authKey;

        public CheckAuthLoginRunnable(String authKey, String phoneNumber, String digestPwd) {
            this.authKey = authKey;
            this.phoneNumber = phoneNumber;
            this.digestPwd = digestPwd;
        }

        @Override
        public void run() {
            HttpUtils.get().url(coreManager.getConfig().CHECK_AUTH_LOGIN)
                    .params("authKey", authKey)
                    .build(true, true)
                    .execute(new BaseCallback<LoginRegisterResult>(LoginRegisterResult.class) {
                        @Override
                        public void onResponse(ObjectResult<LoginRegisterResult> result) {
                            if (Result.checkError(result, Result.CODE_AUTH_LOGIN_SCUESS)) {
                                DialogHelper.dismissProgressDialog();
                                login();
                            } else if (Result.checkError(result, Result.CODE_AUTH_LOGIN_FAILED_1)) {
                                waitAuth(CheckAuthLoginRunnable.this);
                            } else {
                                DialogHelper.dismissProgressDialog();
                                if (!TextUtils.isEmpty(result.getResultMsg())) {
                                    ToastUtil.showToast(mContext, result.getResultMsg());
                                } else {
                                    ToastUtil.showToast(mContext, R.string.tip_server_error);
                                }
                            }
                        }

                        @Override
                        public void onError(Call call, Exception e) {
                            DialogHelper.dismissProgressDialog();
                            ToastUtil.showErrorNet(mContext);
                        }
                    });
        }
    }
}
