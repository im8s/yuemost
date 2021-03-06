package com.sk.weichat.call;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.AnimationDrawable;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.facebook.react.modules.core.PermissionListener;
import com.sk.weichat.R;
import com.sk.weichat.bean.VideoFile;
import com.sk.weichat.bean.message.ChatMessage;
import com.sk.weichat.bean.message.XmppMessage;
import com.sk.weichat.call.talk.MessageTalkJoinEvent;
import com.sk.weichat.call.talk.MessageTalkLeftEvent;
import com.sk.weichat.call.talk.MessageTalkOnlineEvent;
import com.sk.weichat.call.talk.MessageTalkReleaseEvent;
import com.sk.weichat.call.talk.MessageTalkRequestEvent;
import com.sk.weichat.call.talk.TalkUserAdapter;
import com.sk.weichat.call.talk.Talking;
import com.sk.weichat.db.dao.VideoFileDao;
import com.sk.weichat.helper.AvatarHelper;
import com.sk.weichat.helper.CutoutHelper;
import com.sk.weichat.helper.DialogHelper;
import com.sk.weichat.ui.base.BaseActivity;
import com.sk.weichat.util.HttpUtil;
import com.sk.weichat.util.PreferenceUtils;
import com.sk.weichat.util.TimeUtils;
import com.sk.weichat.view.TipDialog;

import org.jitsi.meet.sdk.BuildConfig;
import org.jitsi.meet.sdk.JitsiMeetActivityDelegate;
import org.jitsi.meet.sdk.JitsiMeetActivityInterface;
import org.jitsi.meet.sdk.JitsiMeetConferenceOptions;
import org.jitsi.meet.sdk.JitsiMeetView;
import org.jitsi.meet.sdk.JitsiMeetViewListener;

import java.io.File;
import java.lang.ref.WeakReference;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import de.greenrobot.event.EventBus;
import de.greenrobot.event.Subscribe;
import de.greenrobot.event.ThreadMode;

/**
 * 2018-2-27 ??????????????????????????????
 */
public class JitsiTalk extends BaseActivity implements JitsiMeetActivityInterface {
    private static final String TAG = "JitsiTalk";
    // ????????????
    private static final int RECORD_REQUEST_CODE = 0x01;
    private static final int SEND_ONLINE_STATUS = 756;
    // ???????????????????????????
    public static String time = null;
    private String mLocalHostJitsi = "https://meet.jit.si/";// ????????????
    private String mLocalHost/* = "https://meet.youjob.co/"*/;  // ????????????,???????????????
    // ????????????(?????????????????????????????????????????????????????????)
    private int mCallType;
    private String fromUserId;
    private String toUserId;
    private long startTime = System.currentTimeMillis();// ??????????????????
    private long stopTime; // ??????????????????
    private FrameLayout mFrameLayout;
    private JitsiMeetView mJitsiMeetView;
    // ?????????????????????????????????android 5.0,??????????????????
    private boolean isApi21HangUp;
    private SimpleDateFormat mSimpleDateFormat = new SimpleDateFormat("mm:ss");
    CountDownTimer mCountDownTimer = new CountDownTimer(18000000, 1000) {// ?????????????????????????????????????????????????????????????????????????????????????????????
        @Override
        public void onTick(long millisUntilFinished) {
            time = formatTime();
            JitsiTalk.this.sendBroadcast(new Intent(CallConstants.REFRESH_FLOATING));
        }

        @Override
        public void onFinish() {// 12????????????Finish

        }
    };
    private boolean isOldVersion = true;// ????????????????????????????????? "?????????" ??????????????????????????????????????????????????????????????????????????????ping???????????????
    private boolean isEndCallOpposite;// ???????????????????????????
    private int mPingReceiveFailCount;// ????????????????????? "?????????" ???????????????
    private View btnHangUp;
    private View llTalkFree;
    private ImageView ivTalkingRipple;
    private ImageView vhCurrentHead;
    private RecyclerView rvUserList;
    private View btnTalk;
    private ImageView ivTalk;
    private TextView tvTip;
    private TalkUserAdapter userAdapter;
    // ?????????????????????
    @Nullable
    private Talking talking;
    // ??????jitsi???????????????????????????
    private boolean talkReady = false;
    // ??????3???????????????????????? "?????????" ??????
    CountDownTimer mCallingCountDownTimer = new CountDownTimer(3000, 1000) {
        @Override
        public void onTick(long millisUntilFinished) {

        }

        @Override
        public void onFinish() {// ????????????
            if (!HttpUtil.isGprsOrWifiConnected(JitsiTalk.this)) {
                TipDialog tipDialog = new TipDialog(JitsiTalk.this);
                tipDialog.setmConfirmOnClickListener(getString(R.string.check_network), () -> {
                    leaveJitsi();
                });
                tipDialog.show();
                return;
            }
            if (mCallType == 1 || mCallType == 2 || mCallType == 5 || mCallType == 6) {// ?????????????????????
                if (isEndCallOpposite) {// ???????????????????????? "?????????" ??????
                    // ???????????????????????????Count??????3?????????????????????????????????????????????????????????????????? "?????????" ?????????count+1
                    int maxCount = 10;
                    if (mCallType == 5 || mCallType == 6) {
                        // ?????????ping???????????????
                        maxCount = 4;
                    }
                    if (mPingReceiveFailCount == maxCount) {
                        if (isOldVersion) {
                            return;
                        }
                        Log.e(TAG, "true-->" + TimeUtils.sk_time_current_time());
                        if (!isDestroyed()) {
                            stopTime = System.currentTimeMillis();
                            overCall((int) (stopTime - startTime) / 1000);
                            Toast.makeText(JitsiTalk.this, getString(R.string.tip_opposite_offline_auto__end_call), Toast.LENGTH_SHORT).show();
                            leaveJitsi();
/*
                            TipDialog tipDialog = new TipDialog(Jitsi_connecting_second.this);
                            tipDialog.setmConfirmOnClickListener(getString(R.string.tip_opposite_offline_end_call), () -> {
                                stopTime = System.currentTimeMillis();
                                overCall((int) (stopTime - startTime) / 1000);
                                leaveJitsi();
                            });
                            tipDialog.show();
*/
                        }
                    } else {
                        mPingReceiveFailCount++;
                        Log.e(TAG, "true-->" + mPingReceiveFailCount + "???" + TimeUtils.sk_time_current_time());
                        sendCallingMessage();
                    }
                } else {
                    Log.e(TAG, "false-->" + TimeUtils.sk_time_current_time());
                    sendCallingMessage();
                }
            }
        }
    };
    // ??????????????????????????????????????????????????????
    private RequestTalkTimer requestTalkTimer;
    private Handler sendOnlineHandler = new SendOnlineHandler(this);
    /**
     * ??????????????????????????????
     */
    @Nullable
    private TalkerOnlineTimer talkerOnlineTimer;
    private AnimationDrawable talkingRippleDrawable;

    public static void start(Context ctx, String room, boolean isVideo) {
        Intent intent = new Intent(ctx, JitsiTalk.class);
        if (isVideo) {
            intent.putExtra("type", 2);
        } else {
            intent.putExtra("type", 1);
        }
        intent.putExtra("fromuserid", room);
        intent.putExtra("touserid", room);
        ctx.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        CutoutHelper.setWindowOut(getWindow());
        super.onCreate(savedInstanceState);
        // ?????????????????? | ?????????????????? | Activity????????????????????? | ??????????????????
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_jitsi_talk);
        initData();
        initView();
        initEvent();
        initTalkView();
        EventBus.getDefault().register(this);
        JitsiMeetActivityDelegate.onHostResume(this);
        setSwipeBackEnable(false);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void initTalkView() {
        CutoutHelper.initCutoutHolderTop(getWindow(), findViewById(R.id.vCutoutHolder));
        ivTalkingRipple = findViewById(R.id.ivTalkingRipple);
        llTalkFree = findViewById(R.id.llTalkFree);
        btnHangUp = findViewById(R.id.btnHangUp);
        vhCurrentHead = findViewById(R.id.ivCurrentHead);
        rvUserList = findViewById(R.id.rvUserList);
        rvUserList.setLayoutManager(new GridLayoutManager(this, 3));
        rvUserList.getItemAnimator().setChangeDuration(0);
        userAdapter = new TalkUserAdapter(this);
        rvUserList.setAdapter(userAdapter);

        userAdapter.add(TalkUserAdapter.Item.fromUser(coreManager.getSelf()));

        btnTalk = findViewById(R.id.btnTalk);
        ivTalk = findViewById(R.id.ivTalk);
        tvTip = findViewById(R.id.tvTip);

        btnTalk.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    requestTalk();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    releaseTalk();
                    break;
            }
            return true;
        });

        btnHangUp.setOnClickListener((v) -> {
            leaveJitsi();
        });

        updateTalking();
    }

    private void talkReady() {
        talkReady = true;
        Log.i(TAG, "talkReady() called");
        sendJoinTalkMessage();
        tvTip.setText(R.string.tip_talk_press_in);
    }

    private void talkLeave() {
        if (!talkReady) {
            return;
        }
        talkReady = false;
        Log.i(TAG, "talkLeave() called");
        sendLeftTalkMessage();
    }

    private void releaseTalk() {
        if (!talkReady) {
            return;
        }
        if (requestTalkTimer != null) {
            requestTalkTimer.setStatusReleaseTalk();
            return;
        }
        if (talking != null && TextUtils.equals(talking.userId, getMyUserId())) {
            long releaseTime = TimeUtils.sk_time_current_time();
            long talkLength = releaseTime - talking.requestTime;
            // adapter??????????????????talking????????????????????????
            talking.talkLength = talkLength;
            talking = null;
            onTalkFree();
            updateTalking();
            sendReleaseTalkMessage(releaseTime);
            if (requestTalkTimer == null) {
                requestTalkTimer = new RequestTalkTimer(false);
                requestTalkTimer.start();
            }
        } else {
            Log.i(TAG, "releaseTalk: ??????????????????");
        }
    }

    private void onTalkFree() {
        btnTalk.setBackgroundResource(R.drawable.talk_btn_frame_free);
        ivTalk.setImageResource(R.mipmap.icon_talk_microphone);
        tvTip.setTextColor(getResources().getColor(R.color.black));
        mJitsiMeetView.setAudioMuted();
    }

    private boolean myTalking() {
        return talking != null && TextUtils.equals(talking.userId, getMyUserId());
    }

    private void onTalking() {
        btnTalk.setBackgroundResource(R.drawable.talk_btn_frame_busy);
        ivTalk.setImageResource(R.mipmap.icon_talk_microphone_red);
        tvTip.setTextColor(0xffFF6565);
        mJitsiMeetView.setAudioEnable();
    }

    private void requestTalk() {
        if (!talkReady) {
            return;
        }
        if (requestTalkTimer != null) {
            requestTalkTimer.setStatusTalking();
            return;
        }
        if (talking == null) {
            talking = new Talking(getMyName(), getMyUserId(), TimeUtils.sk_time_current_time());
            onTalking();
            updateTalking();
            sendRequestTalkMessage(talking.requestTime);
            if (requestTalkTimer == null) {
                requestTalkTimer = new RequestTalkTimer(true);
                requestTalkTimer.start();
            }
            // ???????????????5????????????????????????????????????????????????
            sendOnlineHandler.sendEmptyMessage(SEND_ONLINE_STATUS);
        } else if (TextUtils.equals(talking.userId, getMyUserId())) {
            Log.i(TAG, "requestTalk: ???????????????????????????");
        } else {
            Log.i(TAG, "requestTalk: ?????????????????????");
            requestFailed();
        }
    }

    private void requestFailed() {
        Log.w(TAG, "requestFailed() called");
        onTalkFree();
    }

    private void sendOnlineMessage() {
        sendMessage(XmppMessage.TYPE_TALK_ONLINE);
    }

    private void sendJoinTalkMessage() {
        sendMessage(XmppMessage.TYPE_TALK_JOIN);
    }

    private void sendLeftTalkMessage() {
        sendMessage(XmppMessage.TYPE_TALK_LEFT);
    }

    private void sendReleaseTalkMessage(long timeSend) {
        sendMessage(XmppMessage.TYPE_TALK_RELEASE, timeSend);
    }

    private void sendReleaseTalkMessage() {
        sendMessage(XmppMessage.TYPE_TALK_RELEASE);
    }

    private void sendRequestTalkMessage(long timeSend) {
        sendMessage(XmppMessage.TYPE_TALK_REQUEST, timeSend);
    }

    private void sendMessage(int type) {
        sendMessage(type, TimeUtils.sk_time_current_time());
    }

    private void sendMessage(int type, long timeSend) {
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setType(type);

        chatMessage.setFromUserId(coreManager.getSelf().getUserId());
        chatMessage.setFromUserName(coreManager.getSelf().getNickName());
        chatMessage.setToUserId(fromUserId);
        chatMessage.setObjectId(fromUserId);
        chatMessage.setTimeSend(timeSend);
        chatMessage.setPacketId(UUID.randomUUID().toString().replaceAll("-", ""));
        coreManager.sendMucChatMessage(fromUserId, chatMessage);
    }

    private void talkerFree() {
        Log.i(TAG, "talkerFree() called");
        if (talking == null) {
            Log.w(TAG, "talkerFree: talking == null");
            return;
        }
        userAdapter.offline(talking);
        talking = null;
        updateTalking();
    }

    private void talkerOffline() {
        Log.i(TAG, "talkerOffline() called");
        if (talking == null) {
            Log.w(TAG, "talkerOffline: talking == null");
            return;
        }

        talking.name = getString(R.string.tip_talker_ping_failed);
        updateTalking();
    }

    @Subscribe(threadMode = ThreadMode.MainThread)
    public void helloEventBus(final MessageTalkJoinEvent message) {
        if (!TextUtils.equals(message.chatMessage.getObjectId(), fromUserId)) {
            return;
        }
        userAdapter.add(TalkUserAdapter.Item.fromMessage(message.chatMessage));
        sendOnlineMessage();
    }

    @Subscribe(threadMode = ThreadMode.MainThread)
    public void helloEventBus(final MessageTalkOnlineEvent message) {
        if (!TextUtils.equals(message.chatMessage.getObjectId(), fromUserId)) {
            return;
        }
        ChatMessage chatMessage = message.chatMessage;
        String userId = chatMessage.getFromUserId();
        if (talking != null && TextUtils.equals(userId, talking.userId)) {
            // ?????????????????????????????????
            if (talkerOnlineTimer != null) {
                talkerOnlineTimer.cancel();
            }
            talkerOnlineTimer = new TalkerOnlineTimer();
            talkerOnlineTimer.start();
        }
        userAdapter.add(TalkUserAdapter.Item.fromMessage(message.chatMessage));
    }

    @Subscribe(threadMode = ThreadMode.MainThread)
    public void helloEventBus(final MessageTalkLeftEvent message) {
        if (!TextUtils.equals(message.chatMessage.getObjectId(), fromUserId)) {
            return;
        }
        userAdapter.remove(TalkUserAdapter.Item.fromMessage(message.chatMessage));
    }

    @Subscribe(threadMode = ThreadMode.MainThread)
    public void helloEventBus(final MessageTalkRequestEvent message) {
        if (!TextUtils.equals(message.chatMessage.getObjectId(), fromUserId)) {
            return;
        }
        ChatMessage chatMessage = message.chatMessage;
        String name = chatMessage.getFromUserName();
        String userId = chatMessage.getFromUserId();
        long requestTime = chatMessage.getTimeSend();
        if (talking == null) {
            talking = new Talking(name, userId, requestTime);
            Log.d(TAG, "helloEventBus: ????????????" + talking);
            updateTalking();
            talkerOnlineTimer = new TalkerOnlineTimer();
            talkerOnlineTimer.start();
        } else if (TextUtils.equals(talking.userId, getMyUserId())) {
            Talking remote = new Talking(name, userId, requestTime);
            Log.i(TAG, "helloEventBus: ??????, local: " + talking + ", remote: " + remote);
            boolean conflict = requestTime > talking.requestTime;
            if (!conflict && requestTime == talking.requestTime) {
                // ?????????????????????????????????userId, userId???????????????
                try {
                    int selfId = Integer.parseInt(coreManager.getSelf().getUserId());
                    int remoteId = Integer.parseInt(userId);
                    conflict = remoteId < selfId;
                } catch (Exception ignored) {
                }
            }
            if (conflict) {
                Log.i(TAG, "helloEventBus: ????????????");
                talking = remote;
                updateTalking();
                requestFailed();
                // ?????????????????????????????????????????????????????????????????????????????????
                // ??????????????????????????????double????????????????????????????????????????????????
                sendReleaseTalkMessage();
            } else {
                Log.i(TAG, "helloEventBus: ????????????");
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MainThread)
    public void helloEventBus(final MessageTalkReleaseEvent message) {
        if (!TextUtils.equals(message.chatMessage.getObjectId(), fromUserId)) {
            return;
        }
        ChatMessage chatMessage = message.chatMessage;
        String name = chatMessage.getFromUserName();
        String userId = chatMessage.getFromUserId();
        if (talking != null && TextUtils.equals(userId, talking.userId)) {
            Log.i(TAG, "helloEventBus: ????????????, name: " + name);
            // adapter??????????????????talking????????????????????????
            talking.talkLength = chatMessage.getTimeSend() - talking.requestTime;
            talking = null;
            updateTalking();
            if (talkerOnlineTimer != null) {
                talkerOnlineTimer.cancel();
                talkerOnlineTimer = null;
            } else {
                Log.w(TAG, "helloEventBus: talker release but talkerOnlineTimer == null");
            }
        } else {
            Log.i(TAG, "helloEventBus: ????????????????????????name: " + name);
        }
    }

    private AnimationDrawable getTalkingRippleDrawable() {
        if (talkingRippleDrawable != null) {
            return talkingRippleDrawable;
        }
        talkingRippleDrawable = (AnimationDrawable) getResources().getDrawable(R.drawable.talk_btn_frame_busy_ripple);
        return talkingRippleDrawable;
    }

    private void updateTalking() {
        if (talking != null) {
            AvatarHelper.getInstance().displayAvatar(talking.name, talking.userId, vhCurrentHead, false);
            vhCurrentHead.setVisibility(View.VISIBLE);
            llTalkFree.setVisibility(View.GONE);
            AnimationDrawable talkingRippleDrawable = getTalkingRippleDrawable();
            talkingRippleDrawable.start();
            ivTalkingRipple.setImageDrawable(talkingRippleDrawable);
            userAdapter.updateTalking(talking);
        } else {
            vhCurrentHead.setVisibility(View.GONE);
            llTalkFree.setVisibility(View.VISIBLE);
            AnimationDrawable talkingRippleDrawable = getTalkingRippleDrawable();
            talkingRippleDrawable.stop();
            ivTalkingRipple.setImageResource(R.mipmap.talk_btn_frame_large_free);
            // displayAvatar?????????????????????
            vhCurrentHead.setTag(R.id.key_avatar, null);
            userAdapter.updateTalking(null);
        }
    }

    private String getMyName() {
        return coreManager.getSelf().getNickName();
    }

    private String getMyUserId() {
        return coreManager.getSelf().getUserId();
    }

    private void initActionBar() {
        findViewById(R.id.iv_title_left).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                leaveJitsi();
            }
        });
        TextView tvTitle = (TextView) findViewById(R.id.tv_title_center);
        tvTitle.setText(R.string.name_talk);
    }

    @Override
    public void onCoreReady() {
        super.onCoreReady();
        sendCallingMessage();// ??????????????????????????????????????????????????????????????????????????????????????????????????????????????? "?????????" ???????????????
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (!super.onKeyUp(keyCode, event)
                && BuildConfig.DEBUG
                && keyCode == KeyEvent.KEYCODE_MENU
            /*TODO && ReactInstanceManagerHolder.showDevOptionsDialog()*/) {
            return true;
        }
        return false;
    }

    private void initData() {
        mCallType = getIntent().getIntExtra("type", 0);
        fromUserId = getIntent().getStringExtra("fromuserid");
        toUserId = getIntent().getStringExtra("touserid");

        JitsistateMachine.isInCalling = true;
        JitsistateMachine.callingOpposite = toUserId;

        if (mCallType == 1 || mCallType == 2) {// ??????
            mLocalHost = getIntent().getStringExtra("meetUrl");
            if (TextUtils.isEmpty(mLocalHost)) {
                mLocalHost = coreManager.getConfig().JitsiServer;
            }
        } else {
            mLocalHost = coreManager.getConfig().JitsiServer;
        }

        if (TextUtils.isEmpty(mLocalHost)) {
            DialogHelper.tip(mContext, getString(R.string.tip_meet_server_empty));
            finish();
        }

        // mCallingCountDownTimer.start();
    }

    private void leaveJitsi() {
        Log.e(TAG, "leaveJitsi() called ");
        finish();
    }

    /**
     * startWithAudioMuted:??????????????????
     * startWithVideoMuted:??????????????????
     */
    private void initView() {
        mFrameLayout = (FrameLayout) findViewById(R.id.jitsi_view);
        mJitsiMeetView = new JitsiMeetView(this);
        mFrameLayout.addView(mJitsiMeetView);

        // ??????????????????
        JitsiMeetConferenceOptions.Builder options = new JitsiMeetConferenceOptions.Builder()
                .setWelcomePageEnabled(false);
//TODO        mJitsiMeetView.setPictureInPictureEnabled(false);
        options.setAudioMuted(true);
        try {
            options.setServerURL(new URL(mLocalHost));
        } catch (MalformedURLException e) {
            throw new IllegalStateException("jitsi????????????: " + mLocalHost);
        }
        // ???????????????????????????????????????????????????????????????????????????
        options.setRoom("talk" + fromUserId);
        // ????????????
        mJitsiMeetView.join(options.build());
    }

    private void initEvent() {
        mJitsiMeetView.setListener(new JitsiMeetViewListener() {

            @Override
            public void onConferenceWillJoin(Map<String, Object> map) {
                Log.e("jitsi", "??????????????????");
            }

            @Override
            public void onConferenceJoined(Map<String, Object> map) {
                Log.e(TAG, "??????????????????????????????????????????????????????");
                // ?????????runOnUiThread??????onConferenceWillJoin???????????????????????????????????????????????????????????????
                // ?????????????????????????????????
                startTime = System.currentTimeMillis();
                // ????????????
                mCountDownTimer.start();

                talkReady();
            }

            @Override
            public void onConferenceTerminated(Map<String, Object> map) {
                Log.e(TAG, "5");
                // ??????????????????
                if (!isApi21HangUp) {
                    stopTime = System.currentTimeMillis();
                    overCall((int) (stopTime - startTime) / 1000);
                }

                Log.e(TAG, "6");
                JitsiTalk.this.sendBroadcast(new Intent(CallConstants.CLOSE_FLOATING));
                finish();
            }
        });
    }

    public void sendCallingMessage() {
        isEndCallOpposite = true;

        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setType(XmppMessage.TYPE_IN_CALLING);

        chatMessage.setFromUserId(coreManager.getSelf().getUserId());
        chatMessage.setFromUserName(coreManager.getSelf().getNickName());
        chatMessage.setToUserId(toUserId);
        chatMessage.setTimeSend(TimeUtils.sk_time_current_time());
        chatMessage.setPacketId(UUID.randomUUID().toString().replaceAll("-", ""));
        coreManager.sendChatMessage(toUserId, chatMessage);

        mCallingCountDownTimer.start();// ??????????????????
    }

    @Subscribe(threadMode = ThreadMode.MainThread)
    public void helloEventBus(final MessageCallingEvent message) {
        if (message.chatMessage.getType() == XmppMessage.TYPE_IN_CALLING) {
            if (message.chatMessage.getFromUserId().equals(toUserId)) {
                isOldVersion = false;
                // ?????? "?????????" ????????????????????????????????????????????????????????????
                Log.e(TAG, "MessageCallingEvent-->" + TimeUtils.sk_time_current_time());
                mPingReceiveFailCount = 0;// ???count??????0
                isEndCallOpposite = false;
            }
        }
    }

    // ????????????
    @Subscribe(threadMode = ThreadMode.MainThread)
    public void helloEventBus(final MessageHangUpPhone message) {
        if (message.chatMessage.getFromUserId().equals(fromUserId)
                || message.chatMessage.getFromUserId().equals(toUserId)) {// ?????????????????????????????? ???????????????
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.LOLLIPOP) {
                isApi21HangUp = true;
                TipDialog tip = new TipDialog(JitsiTalk.this);
                tip.setmConfirmOnClickListener(getString(R.string.av_hand_hang), new TipDialog.ConfirmOnClickListener() {
                    @Override
                    public void confirm() {
                        hideBottomUIMenu();
                    }
                });
                tip.show();
                return;
            }

            // ???????????????
            sendBroadcast(new Intent(CallConstants.CLOSE_FLOATING));
            leaveJitsi();
        }
    }

    /*******************************************
     * Method
     ******************************************/
    // ???????????????XMPP??????
    private void overCall(int time) {
        if (mCallType == 1) {
            EventBus.getDefault().post(new MessageEventCancelOrHangUp(104, toUserId,
                    getString(R.string.sip_canceled) + getString(R.string.voice_chat),
                    time));
        } else if (mCallType == 2) {
            EventBus.getDefault().post(new MessageEventCancelOrHangUp(114, toUserId,
                    getString(R.string.sip_canceled) + getString(R.string.voice_chat),
                    time));
        } else if (mCallType == 5) {
            EventBus.getDefault().post(new MessageEventCancelOrHangUp(134, toUserId,
                    getString(R.string.sip_canceled) + getString(R.string.name_talk),
                    time));
        }
        talkLeave();
    }

    private String formatTime() {
        Date date = new Date(new Date().getTime() - startTime);
        return mSimpleDateFormat.format(date);
    }

    // ??????????????????
    private void hideBottomUIMenu() {
        View v = this.getWindow().getDecorView();
        v.setSystemUiVisibility(View.GONE);
    }

    /*******************************************
     * ??????????????????????????????
     ******************************************/
    public void saveScreenRecordFile() {
        // ??????????????????
        String imNewestScreenRecord = PreferenceUtils.getString(getApplicationContext(), "IMScreenRecord");
        File file = new File(imNewestScreenRecord);
        if (file.exists() && file.getName().trim().toLowerCase().endsWith(".mp4")) {
            VideoFile videoFile = new VideoFile();
            videoFile.setCreateTime(TimeUtils.f_long_2_str(getScreenRecordFileCreateTime(file.getName())));
            videoFile.setFileLength(getScreenRecordFileTimeLen(file.getPath()));
            videoFile.setFileSize(file.length());
            videoFile.setFilePath(file.getPath());
            videoFile.setOwnerId(coreManager.getSelf().getUserId());
            VideoFileDao.getInstance().addVideoFile(videoFile);
        }
    }

    private long getScreenRecordFileCreateTime(String srf) {
        int dot = srf.lastIndexOf('.');
        return Long.parseLong(srf.substring(0, dot));
    }

    private long getScreenRecordFileTimeLen(String srf) {
        long duration;
        MediaPlayer player = new MediaPlayer();
        try {
            player.setDataSource(srf);
            player.prepare();
            duration = player.getDuration() / 1000;
        } catch (Exception e) {
            duration = 10;
            e.printStackTrace();
        }
        player.release();
        return duration;
    }

    /*******************************************
     * ????????????
     ******************************************/
    @Override
    public void onBackPressed() {
        // ?????????????????????????????????????????????finish???
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        JitsiMeetActivityDelegate.onNewIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (JitsistateMachine.isFloating) {
            sendBroadcast(new Intent(CallConstants.CLOSE_FLOATING));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        // ??????????????????
        JitsiMeetActivityDelegate.onHostPause(this);
        JitsistateMachine.reset();

        JitsiMeetActivityDelegate.onBackPressed();
        mJitsiMeetView.dispose();
        JitsiMeetActivityDelegate.onHostDestroy(this);

        EventBus.getDefault().unregister(this);

        if (mCountDownTimer != null) {
            mCountDownTimer.cancel();
        }
        Log.e(TAG, "onDestory");
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(
            final int requestCode,
            final String[] permissions,
            final int[] grantResults) {
        JitsiMeetActivityDelegate.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    public void requestPermissions(String[] permissions, int requestCode, PermissionListener listener) {
        JitsiMeetActivityDelegate.requestPermissions(this, permissions, requestCode, listener);
    }

    @Override
    public void onPointerCaptureChanged(boolean hasCapture) {
        Log.d(TAG, "onPointerCaptureChanged() called with: hasCapture = [" + hasCapture + "]");
    }

    private static class SendOnlineHandler extends Handler {
        private final WeakReference<JitsiTalk> weakRef;

        SendOnlineHandler(JitsiTalk r) {
            weakRef = new WeakReference<>(r);
        }

        @Override
        public void handleMessage(Message msg) {
            JitsiTalk r = weakRef.get();
            if (r == null) {
                return;
            }
            if (msg.what == SEND_ONLINE_STATUS) {
                if (r.myTalking()) {
                    r.sendOnlineMessage();
                    r.sendOnlineHandler.sendEmptyMessageDelayed(SEND_ONLINE_STATUS, TimeUnit.SECONDS.toMillis(5));
                }
            }
        }
    }

    private class RequestTalkTimer extends CountDownTimer {

        private boolean statusTalking;

        public RequestTalkTimer(boolean statusTalking) {
            super(1000, 1000);
            this.statusTalking = statusTalking;
        }

        void setStatusReleaseTalk() {
            statusTalking = false;
        }

        void setStatusTalking() {
            statusTalking = true;
        }

        @Override
        public void onTick(long millisUntilFinished) {
        }

        @Override
        public void onFinish() {
            requestTalkTimer = null;
            if (statusTalking) {
                requestTalk();
            } else {
                releaseTalk();
            }
        }
    }

    private class TalkerOnlineTimer extends CountDownTimer {
        TalkerOnlineTimer() {
            super(TimeUnit.SECONDS.toMillis(15), TimeUnit.SECONDS.toMillis(1));
        }

        @Override
        public void onTick(long millisUntilFinished) {
            // 10????????????????????????talker????????????
            if (millisUntilFinished > TimeUnit.SECONDS.toMillis(5)) {
                return;
            }
            talkerOffline();
        }

        @Override
        public void onFinish() {
            talkerOnlineTimer = null;
            talkerFree();
        }
    }
}
