package com.sk.weichat.view;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Dialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.CountDownTimer;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.Transformation;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.TextView;
import android.widget.Toast;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sk.weichat.MyApplication;
import com.sk.weichat.R;
import com.sk.weichat.audio_x.VoiceManager;
import com.sk.weichat.audio_x.VoicePlayer;
import com.sk.weichat.bean.Friend;
import com.sk.weichat.bean.RoomMember;
import com.sk.weichat.bean.User;
import com.sk.weichat.bean.collection.CollectionEvery;
import com.sk.weichat.bean.message.ChatMessage;
import com.sk.weichat.bean.message.XmppMessage;
import com.sk.weichat.broadcast.OtherBroadcast;
import com.sk.weichat.db.dao.ChatMessageDao;
import com.sk.weichat.db.dao.FriendDao;
import com.sk.weichat.db.dao.RoomMemberDao;
import com.sk.weichat.helper.DialogHelper;
import com.sk.weichat.socket.EMConnectionManager;
import com.sk.weichat.ui.base.CoreManager;
import com.sk.weichat.ui.message.EventMoreSelected;
import com.sk.weichat.ui.message.InstantMessageActivity;
import com.sk.weichat.ui.message.MessageRemindActivity;
import com.sk.weichat.ui.message.multi.RoomReadListActivity;
import com.sk.weichat.util.AsyncUtils;
import com.sk.weichat.util.Base64;
import com.sk.weichat.util.Constants;
import com.sk.weichat.util.DisplayUtil;
import com.sk.weichat.util.FileUtil;
import com.sk.weichat.util.HtmlUtils;
import com.sk.weichat.util.PreferenceUtils;
import com.sk.weichat.util.StringUtils;
import com.sk.weichat.util.TimeUtils;
import com.sk.weichat.util.ToastUtil;
import com.sk.weichat.util.secure.AES;
import com.sk.weichat.util.secure.chat.SecureChatUtil;
import com.sk.weichat.view.chatHolder.AChatHolderInterface;
import com.sk.weichat.view.chatHolder.ChatHolderFactory;
import com.sk.weichat.view.chatHolder.ChatHolderFactory.ChatHolderType;
import com.sk.weichat.view.chatHolder.ChatHolderListener;
import com.sk.weichat.view.chatHolder.MessageEventClickFire;
import com.sk.weichat.view.chatHolder.TextReplayViewHolder;
import com.sk.weichat.view.chatHolder.TextViewHolder;
import com.sk.weichat.view.chatHolder.VoiceViewHolder;
import com.sk.weichat.xmpp.listener.ChatMessageListener;
import com.xuan.xuanhttplibrary.okhttp.HttpUtils;
import com.xuan.xuanhttplibrary.okhttp.callback.BaseCallback;
import com.xuan.xuanhttplibrary.okhttp.result.ObjectResult;
import com.xuan.xuanhttplibrary.okhttp.result.Result;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.greenrobot.event.EventBus;
import okhttp3.Call;

import static com.sk.weichat.bean.message.XmppMessage.TYPE_FILE;
import static com.sk.weichat.bean.message.XmppMessage.TYPE_IMAGE;
import static com.sk.weichat.bean.message.XmppMessage.TYPE_TEXT;
import static com.sk.weichat.bean.message.XmppMessage.TYPE_VIDEO;
import static com.sk.weichat.bean.message.XmppMessage.TYPE_VOICE;

@SuppressWarnings("unused")
public class ChatContentView extends PullDownListView implements ChatBottomView.MoreSelectMenuListener {

    // ??????????????????????????????
    public Map<String, Bitmap> mCacheMap = new HashMap<>();
    public boolean isDevice;
    Map<String, String> cache = new HashMap<>();
    private boolean isGroupChat;// ?????????????????????????????????????????????
    private boolean isShowReadPerson;// ???????????????????????????
    private boolean isShowMoreSelect;// ?????????????????????????????????
    private boolean isScrollBottom;// ?????????????????????????????????????????????????????????????????????
    private int mGroupLevel = 3;// ??????????????????????????????????????????????????????????????????(default==3????????????)
    private int mCurClickPos = -1;// ???????????????position
    private String mRoomNickName; // ??????????????????????????????????????????
    private String mToUserId;// ??????self.userId???mToUserId ?????????????????????
    private String mRoomId;
    private User mLoginUser;// ?????????????????????
    private Context mContext; // ???????????????
    private ChatListType mCurChatType; // ??????????????????????????????
    private LayoutInflater mInflater;// ???????????????
    private ChatBottomView mChatBottomView; // ?????????????????????
    private AutoVoiceModule aVoice; // ????????????????????????????????????????????????
    private ChatContentAdapter mChatContentAdapter;// ???????????????
    private MessageEventListener mMessageEventListener; // ?????????????????????
    private ChatTextClickPpWindow mChatPpWindow; // ???????????????
    // ????????????????????????
    private List<ChatMessage> mChatMessages;
    // ???????????????????????????packedId??????
    private Set<String> mDeletedChatMessageId = new HashSet<>();
    // ???????????? ????????????????????????????????????
    private Map<String, CountDownTimer> mTextBurningMaps = new HashMap<>();
    // ???????????????????????????????????????
    private Map<String, String> mFireVoiceMaps = new HashMap<>();
    // ??????????????????????????????
    private Map<String, String> mRemarksMap = new HashMap<>();
    // ???????????????
    private Map<String, Integer> memberMap = new HashMap<>();
    // ????????????item????????????
    private Map<String, Long> clickHistoryMap = new HashMap<>();

    // ???????????????
    private Runnable mScrollTask = new Runnable() {
        @Override
        public void run() {
            if (mChatMessages == null) {
                return;
            }
            setSelection(mChatMessages.size());
        }
    };
    private Collection<OnScrollListener> onScrollListenerList = new ArrayList<>();
    private boolean secret;

    public ChatContentView(Context context) {
        this(context, null);
    }

    public ChatContentView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    // ???????????????????????????????????????
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (oldh > h) {
            removeCallbacks(mScrollTask);
            //  int delay = getResources().getInteger(android.R.integer.config_shortAnimTime); // 200
            postDelayed(mScrollTask, 150);
        }
    }

    private void init(Context context) {
        mContext = context;
        mInflater = LayoutInflater.from(mContext);

        setCacheColorHint(0x00000000);
        mLoginUser = CoreManager.requireSelf(context);
        mRoomNickName = mLoginUser.getNickName();
        aVoice = new AutoVoiceModule();

        VoicePlayer.instance().addVoicePlayListener(new VoicePlayListener());

        setOnScrollListener(new OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                for (OnScrollListener listener : onScrollListenerList) {
                    listener.onScrollStateChanged(view, scrollState);
                }
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                isScrollBottom = firstVisibleItem + visibleItemCount >= totalItemCount;
                for (OnScrollListener listener : onScrollListenerList) {
                    listener.onScroll(view, firstVisibleItem, visibleItemCount, totalItemCount);
                }
            }
        });

        mChatContentAdapter = new ChatContentAdapter();
        setAdapter(mChatContentAdapter);
    }

    // ???????????????????????????
    public boolean shouldScrollToBottom() {
        return isScrollBottom;
    }

    // ???????????????????????????????????????
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            if (mMessageEventListener != null) {
                mMessageEventListener.onEmptyTouch();
            }
        }
        return super.onTouchEvent(event);
    }

    // ???????????????userid
    public void setToUserId(String toUserId) {
        mToUserId = toUserId;
    }

    // ???????????????userid
    public void setRoomId(String roomid) {
        mRoomId = roomid;
    }

    // ???????????????
    public void setIsShowMoreSelect(boolean isShowMoreSelect) {
        this.isShowMoreSelect = isShowMoreSelect;
    }

    // ??????????????????????????????????????????????????????
    public void setCurGroup(boolean group, String nickName) {
        isGroupChat = group;
        if (!TextUtils.isEmpty(nickName)) {
            mRoomNickName = nickName;
        }
        if (mRemarksMap.size() == 0) {
            AsyncUtils.doAsync(mContext, new AsyncUtils.Function<AsyncUtils.AsyncContext<Context>>() {
                @Override
                public void apply(AsyncUtils.AsyncContext<Context> contextAsyncContext) throws Exception {
                    List<Friend> friendList = FriendDao.getInstance().getAllFriends(mLoginUser.getUserId());
                    for (int i = 0; i < friendList.size(); i++) {
                        if (!TextUtils.isEmpty(friendList.get(i).getRemarkName())) {
                            mRemarksMap.put(friendList.get(i).getUserId(), friendList.get(i).getRemarkName());
                        }
                    }
                }
            });
        }
    }

    public boolean isGroupChat() {
        return isGroupChat;
    }

    // ???????????????????????????????????????
    public void setRole(int role) {
        this.mGroupLevel = role;
    }

    public void setChatBottomView(ChatBottomView chatBottomView) {
        this.mChatBottomView = chatBottomView;
        if (mChatBottomView != null) {
            mChatBottomView.setMoreSelectMenuListener(this);
        }
    }

    public void setData(List<ChatMessage> chatMessages) {
        mChatMessages = chatMessages;
        if (mChatMessages == null) {
            mChatMessages = new ArrayList<>();
        }
        notifyDataSetChanged();
    }

    // ???????????????????????????
    public void setRoomMemberList(List<RoomMember> memberList) {
        memberMap.clear();
        for (RoomMember member : memberList) {
            memberMap.put(member.getUserId(), member.getRole());
        }
        if (shouldScrollToBottom()) {
            notifyDataSetInvalidated(true);
        } else {
            notifyDataSetChanged();
        }
    }

    // ?????????????????????????????????
    public void removeItemMessage(final String packedId) {
        mDeletedChatMessageId.add(packedId);
        notifyDataSetChanged();
    }

    // ????????????
    public void notifyDataSetInvalidated(final int position) {
        notifyDataSetChanged();
        if (mChatMessages.size() > position) {
            this.post(() -> setSelection(position));
        }
    }

    /**
     * ?????????????????????????????????????????????????????????
     * ??????????????????????????????????????????
     */
    public void notifyDataSetAddedItemsToTop(final int count) {
        int oldPosition = getFirstVisiblePosition();
        View firstView = getChildAt(0);
        int oldTop = firstView == null ? 0 : firstView.getTop();
        notifyDataSetChanged();
        int position = count + oldPosition;
        if (mChatMessages.size() > position) {
            // ???????????????????????????post, ?????????????????????????????????onScroll???????????????
            // ?????????????????????post, ???????????????
            setSelectionFromTop(position, oldTop);
        }
    }

    public void notifyDataSetInvalidated(boolean scrollToBottom) {
/*
        notifyDataSetChanged();
        if (scrollToBottom && mChatMessages.size() > 0) {
            setSelection(mChatMessages.size());
        }
*/
        // ????????????????????????setSelection????????????????????????????????????????????????notifyDataSetInvalidatedForSetSelectionInvalid????????????
        notifyDataSetInvalidatedForSetSelectionInvalid(scrollToBottom);
    }

    // ???????????????????????????setSelection????????????????????? ???????????????????????????????????????
    // https://blog.csdn.net/santamail/article/details/38821763
    // ?????????????????????????????????????????????????????????????????????
    public void notifyDataSetInvalidatedForSetSelectionInvalid(boolean scrollToBottom) {
        notifyDataSetChanged();
        if (scrollToBottom && mChatMessages.size() > 0) {
            if (mChatContentAdapter != null) {
                this.setAdapter(mChatContentAdapter);
            }
            setSelection(mChatMessages.size());
        }
    }

    public void notifyDataSetChanged() {
        if (mChatContentAdapter != null) {
            mChatContentAdapter.notifyDataSetChanged();
        }
    }

    // ?????????????????????
    public void setMessageEventListener(MessageEventListener listener) {
        mMessageEventListener = listener;
    }

    // ??????????????????????????? ?????? ?????? ??????
    public void setChatListType(ChatListType type) {
        mCurChatType = type;
    }

    // ???????????????????????????
    private void startRemoveAnim(View view, ChatMessage message, int position) {
        Animation anim = new Animation() {
            @Override
            protected void applyTransformation(float interpolatedTime, Transformation t) {
                view.setAlpha(1f - interpolatedTime);
            }
        };

        anim.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {

            }

            @Override
            public void onAnimationEnd(Animation animation) {
                mChatMessages.remove(message);
                mDeletedChatMessageId.remove(message);
                view.clearAnimation();
                notifyDataSetChanged();
            }

            @Override
            public void onAnimationRepeat(Animation animation) {

            }
        });

        anim.setDuration(1000);
        view.startAnimation(anim);
    }

    // ???????????????????????????????????????????????????????????????????????????????????????????????????????????? ???????????????????????? ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????

    // ????????????????????????????????????
    private void startCountDownTimer(long time, AChatHolderInterface holder, ChatMessage message) {
        if (time < 1000) {
            mTextBurningMaps.remove(message.getPacketId());
            EventBus.getDefault().post(new MessageEventClickFire("delete", message.getPacketId()));
            removeItemMessage(message.getPacketId());
            return;
        }

        TextView tvFireTime;
        if (message.getType() == XmppMessage.TYPE_TEXT) {
            TextViewHolder textViewHolder = (TextViewHolder) holder;
            tvFireTime = textViewHolder.tvFireTime;
        } else {
            TextReplayViewHolder textReplayViewHolder = (TextReplayViewHolder) holder;
            tvFireTime = textReplayViewHolder.tvFireTime;
        }
        CountDownTimer mNewCountDownTimer = new CountDownTimer(time, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                tvFireTime.setText(String.valueOf(millisUntilFinished / 1000));
                message.setReadTime(millisUntilFinished);
                ChatMessageDao.getInstance().updateMessageReadTime(mLoginUser.getUserId(), message.getFromUserId(), message.getPacketId(), millisUntilFinished);
            }

            @Override
            public void onFinish() {
                mTextBurningMaps.remove(message.getPacketId());
                EventBus.getDefault().post(new MessageEventClickFire("delete", message.getPacketId()));

                removeItemMessage(message.getPacketId());
            }
        }.start();
        mTextBurningMaps.put(message.getPacketId(), mNewCountDownTimer);
    }

    // ?????????????????????????????? || ?????? ??????
    private void clickFireText(AChatHolderInterface holder, ChatMessage message) {
        TextView mTvContent, tvFireTime;
        if (message.getType() == TYPE_TEXT) {
            TextViewHolder textViewHolder = (TextViewHolder) holder;
            mTvContent = textViewHolder.mTvContent;
            tvFireTime = textViewHolder.tvFireTime;
        } else {
            TextReplayViewHolder textReplayViewHolder = (TextReplayViewHolder) holder;
            mTvContent = textReplayViewHolder.mTvContent;
            tvFireTime = textReplayViewHolder.tvFireTime;
        }

        mTvContent.setTextColor(getResources().getColor(R.color.black));
        String s = StringUtils.replaceSpecialChar(message.getContent());
        CharSequence charSequence = HtmlUtils.transform200SpanString(s, true);
        mTvContent.setText(charSequence);

        mTvContent.post(() -> {
            final long time = mTvContent.getLineCount() * 10000;// ?????????????????????10s
            tvFireTime.setText(String.valueOf(time / 1000));
            tvFireTime.setVisibility(VISIBLE);
            message.setReadTime(time);

            startCountDownTimer(time, holder, message);
        });
    }

    // ???????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????? ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????

    // ?????? ?????? ???????????????????????????
    private boolean isNullSelectMore(List<ChatMessage> list) {
        if (list == null || list.size() == 0) {
            return true;
        }

        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).isMoreSelected) {
                return false;
            }
        }
        return true;
    }

    private boolean isContainFireMsgSelectMore(List<ChatMessage> list) {
        if (list == null || list.size() == 0) {
            return false;
        }

        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).isMoreSelected && list.get(i).getIsReadDel()) {
                return true;
            }
        }
        return false;
    }

    // ????????? ??????????????????
    @Override
    public void clickForwardMenu() {
        final Dialog mForwardDialog = new Dialog(mContext, R.style.BottomDialog);
        View contentView = mInflater.inflate(R.layout.forward_dialog, null);
        mForwardDialog.setContentView(contentView);
        ViewGroup.LayoutParams layoutParams = contentView.getLayoutParams();
        layoutParams.width = getResources().getDisplayMetrics().widthPixels;
        contentView.setLayoutParams(layoutParams);
        mForwardDialog.setCanceledOnTouchOutside(true);
        mForwardDialog.getWindow().setGravity(Gravity.BOTTOM);
        mForwardDialog.getWindow().setWindowAnimations(R.style.BottomDialog_Animation);
        mForwardDialog.show();
        mForwardDialog.findViewById(R.id.single_forward).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {// ????????????
                mForwardDialog.dismiss();
                if (isNullSelectMore(mChatMessages)) {
                    Toast.makeText(mContext, mContext.getString(R.string.name_connot_null), Toast.LENGTH_SHORT).show();
                    return;
                }
                // ?????????????????????
                Intent intent = new Intent(mContext, InstantMessageActivity.class);
                intent.putExtra(Constants.IS_MORE_SELECTED_INSTANT, true);
                mContext.startActivity(intent);
            }
        });
        mForwardDialog.findViewById(R.id.sum_forward).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {// ????????????
                mForwardDialog.dismiss();
                if (isNullSelectMore(mChatMessages)) {
                    Toast.makeText(mContext, mContext.getString(R.string.name_connot_null), Toast.LENGTH_SHORT).show();
                    return;
                }
                // ?????????????????????
                Intent intent = new Intent(mContext, InstantMessageActivity.class);
                intent.putExtra(Constants.IS_MORE_SELECTED_INSTANT, true);
                intent.putExtra(Constants.IS_SINGLE_OR_MERGE, true);
                mContext.startActivity(intent);
            }
        });
        mForwardDialog.findViewById(R.id.cancel).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                mForwardDialog.dismiss();
            }
        });
    }

    // ???????????????????????????
    @Override
    public void clickCollectionMenu() {
        if (isNullSelectMore(mChatMessages)) {
            Toast.makeText(mContext, mContext.getString(R.string.name_connot_null), Toast.LENGTH_SHORT).show();
            return;
        }
        if (isContainFireMsgSelectMore(mChatMessages)) {
            ToastUtil.showToast(mContext, mContext.getString(R.string.tip_cannot_collect_burn));
            return;
        }
        if (isContainFireMsgSelectMore(mChatMessages)) {
            ToastUtil.showToast(mContext, mContext.getString(R.string.tip_cannot_collect_burn));
            return;
        }
        String tip;
        if (MyApplication.IS_SUPPORT_SECURE_CHAT) {
            tip = getContext().getString(R.string.tip_collect_allow_type) +
                    getContext().getString(R.string.dont_support_tip, getContext().getString(R.string.collection));
        } else {
            tip = getContext().getString(R.string.tip_collect_allow_type);
        }
        SelectionFrame selectionFrame = new SelectionFrame(mContext);
        selectionFrame.setSomething(null, tip, getContext().getString(R.string.cancel), getContext().getString(R.string.collection),
                new SelectionFrame.OnSelectionFrameClickListener() {
                    @Override
                    public void cancelClick() {

                    }

                    @Override
                    public void confirmClick() {
                        List<ChatMessage> temp = new ArrayList<>();
                        for (int i = 0; i < mChatMessages.size(); i++) {
                            if (mChatMessages.get(i).isMoreSelected
                                    && TextUtils.isEmpty(mChatMessages.get(i).getSignature())
                                    && (mChatMessages.get(i).getType() == TYPE_TEXT
                                    || mChatMessages.get(i).getType() == TYPE_IMAGE
                                    || mChatMessages.get(i).getType() == TYPE_VOICE
                                    || mChatMessages.get(i).getType() == TYPE_VIDEO
                                    || mChatMessages.get(i).getType() == TYPE_FILE)) {
                                temp.add(mChatMessages.get(i));
                            }
                        }
                        moreSelectedCollection(temp);
                        // ??????EventBus???????????????????????????????????????
                        EventBus.getDefault().post(new EventMoreSelected("MoreSelectedCollection", false, isGroupChat()));
                    }
                });
        selectionFrame.show();
    }

    // ???????????????????????????
    @Override
    public void clickDeleteMenu() {
        if (isNullSelectMore(mChatMessages)) {
            Toast.makeText(mContext, mContext.getString(R.string.name_connot_null), Toast.LENGTH_SHORT).show();
            return;
        }
        final Dialog mDeleteDialog = new Dialog(mContext, R.style.BottomDialog);
        View contentView = mInflater.inflate(R.layout.delete_dialog, null);
        mDeleteDialog.setContentView(contentView);
        ViewGroup.LayoutParams layoutParams = contentView.getLayoutParams();
        layoutParams.width = getResources().getDisplayMetrics().widthPixels;
        contentView.setLayoutParams(layoutParams);
        mDeleteDialog.setCanceledOnTouchOutside(true);
        mDeleteDialog.getWindow().setGravity(Gravity.BOTTOM);
        mDeleteDialog.getWindow().setWindowAnimations(R.style.BottomDialog_Animation);
        mDeleteDialog.show();
        mDeleteDialog.findViewById(R.id.delete_message).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                mDeleteDialog.dismiss();
                EventBus.getDefault().post(new EventMoreSelected("MoreSelectedDelete", false, isGroupChat()));
            }
        });

        mDeleteDialog.findViewById(R.id.cancel).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                mDeleteDialog.dismiss();
            }
        });
    }

    // ????????? ????????????
    @Override
    public void clickEmailMenu() {
        if (isNullSelectMore(mChatMessages)) {
            Toast.makeText(mContext, mContext.getString(R.string.name_connot_null), Toast.LENGTH_SHORT).show();
            return;
        }
        final Dialog mEmailDialog = new Dialog(mContext, R.style.BottomDialog);
        View contentView = mInflater.inflate(R.layout.email_dialog, null);
        mEmailDialog.setContentView(contentView);
        ViewGroup.LayoutParams layoutParams = contentView.getLayoutParams();
        layoutParams.width = getResources().getDisplayMetrics().widthPixels;
        contentView.setLayoutParams(layoutParams);
        mEmailDialog.setCanceledOnTouchOutside(true);
        mEmailDialog.getWindow().setGravity(Gravity.BOTTOM);
        mEmailDialog.getWindow().setWindowAnimations(R.style.BottomDialog_Animation);
        mEmailDialog.show();
        mEmailDialog.findViewById(R.id.save_message).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                mEmailDialog.dismiss();
                SelectionFrame selectionFrame = new SelectionFrame(mContext);
                selectionFrame.setSomething(null, getContext().getString(R.string.save_only_image), getContext().getString(R.string.cancel), getContext().getString(R.string.save),
                        new SelectionFrame.OnSelectionFrameClickListener() {
                            @Override
                            public void cancelClick() {

                            }

                            @Override
                            public void confirmClick() {
                                for (int i = 0; i < mChatMessages.size(); i++) {
                                    if (mChatMessages.get(i).isMoreSelected && mChatMessages.get(i).getType() == TYPE_IMAGE) {
                                        FileUtil.downImageToGallery(mContext, mChatMessages.get(i).getContent());
                                    }
                                }
                                EventBus.getDefault().post(new EventMoreSelected("MoreSelectedEmail", false, isGroupChat()));
                            }
                        });
                selectionFrame.show();
            }
        });

        mEmailDialog.findViewById(R.id.cancel).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                mEmailDialog.dismiss();
            }
        });
    }

    /**
     * ???????????????????????????????????????????????????????????????
     *
     * @param flag ???????????????true, ??????????????????false,
     */
    private String collectionParam(List<ChatMessage> messageList, boolean flag, boolean isGroup, String toUserId) {
        JSONArray array = new JSONArray();
        for (ChatMessage message : messageList) {
            int type = 6;
            if (flag) {// ??????
                if (message.getType() == TYPE_IMAGE) {
                    type = CollectionEvery.TYPE_IMAGE;
                } else if (message.getType() == TYPE_VIDEO) {
                    type = CollectionEvery.TYPE_VIDEO;
                } else if (message.getType() == TYPE_FILE) {
                    type = CollectionEvery.TYPE_FILE;
                } else if (message.getType() == TYPE_VOICE) {
                    type = CollectionEvery.TYPE_VOICE;
                } else if (message.getType() == TYPE_TEXT) {
                    type = CollectionEvery.TYPE_TEXT;
                }
            }
            JSONObject json = new JSONObject();
            json.put("type", String.valueOf(type));
            json.put("msg", message.getContent());
            if (flag) {
                // ????????????id
                json.put("msgId", message.getPacketId());
                if (isGroup) {
                    // ????????????????????????jid
                    json.put("roomJid", toUserId);
                }
            } else {
                // ??????url
                json.put("url", message.getContent());
            }
            array.add(json);
        }
        return JSON.toJSONString(array);
    }

    /**
     * ??????????????? && ??????
     * ???????????????Type 6.??????
     * ??????Type    1.?????? 2.?????? 3.?????? 4.?????? 5.??????
     */
    public void collectionEmotion(ChatMessage message, final boolean flag, boolean isGroup, String toUserId) {
        if (TextUtils.isEmpty(message.getContent())) {
            return;
        }
        DialogHelper.showDefaulteMessageProgressDialog(mContext);
        Map<String, String> params = new HashMap<>();
        params.put("access_token", CoreManager.requireSelfStatus(getContext()).accessToken);
        params.put("emoji", collectionParam(Collections.singletonList(message), flag, isGroup, toUserId));

        HttpUtils.post().url(CoreManager.requireConfig(MyApplication.getInstance()).Collection_ADD)
                .params(params)
                .build()
                .execute(new BaseCallback<Void>(Void.class) {

                    @Override
                    public void onResponse(ObjectResult<Void> result) {
                        DialogHelper.dismissProgressDialog();
                        if (Result.checkSuccess(mContext, result)) {
                            Toast.makeText(mContext, mContext.getString(R.string.collection_success), Toast.LENGTH_SHORT).show();
                            if (!flag) { // ???????????????
                                // ???????????????????????????url???????????????????????????????????????????????????
                                // PreferenceUtils.putInt(mContext, self.getUserId() + message.getContent(), 1);
                                // ??????????????????????????????
                                MyApplication.getInstance().sendBroadcast(new Intent(OtherBroadcast.CollectionRefresh));
                            }
                        }
                    }

                    @Override
                    public void onError(Call call, Exception e) {
                        DialogHelper.dismissProgressDialog();
                        ToastUtil.showNetError(mContext);
                    }
                });
    }

    /**
     * ?????? ??????
     */
    public void moreSelectedCollection(List<ChatMessage> chatMessageList) {
        if (chatMessageList == null || chatMessageList.size() <= 0) {
            Toast.makeText(mContext, mContext.getString(R.string.name_connot_null), Toast.LENGTH_SHORT).show();
            return;
        }

        Map<String, String> params = new HashMap<>();
        params.put("access_token", CoreManager.requireSelfStatus(getContext()).accessToken);
        params.put("emoji", collectionParam(chatMessageList, true, isGroupChat, mToUserId));

        HttpUtils.post().url(CoreManager.requireConfig(MyApplication.getInstance()).Collection_ADD)
                .params(params)
                .build()
                .execute(new BaseCallback<Void>(Void.class) {

                    @Override
                    public void onResponse(ObjectResult<Void> result) {
                        if (result.getResultCode() == 1) {
                            Toast.makeText(mContext, mContext.getString(R.string.collection_success), Toast.LENGTH_SHORT).show();
                        } else if (!TextUtils.isEmpty(result.getResultMsg())) {
                            ToastUtil.showToast(mContext, result.getResultMsg());
                        } else {
                            ToastUtil.showToast(mContext, R.string.tip_server_error);
                        }
                    }

                    @Override
                    public void onError(Call call, Exception e) {
                        ToastUtil.showNetError(mContext);
                    }
                });
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        for (Map.Entry<String, Bitmap> entry : mCacheMap.entrySet()) {
            Bitmap bitmap = entry.getValue();
            bitmap.recycle();
            bitmap = null;
        }
        mCacheMap.clear();
        System.gc();
    }

    public void addOnScrollListener(OnScrollListener onScrollListener) {
        onScrollListenerList.add(onScrollListener);
    }

    public void setSecret(boolean secret) {
        this.secret = secret;
    }

    // ???????????????????????????????????????????????????????????????????????????????????????????????????????????? ????????? ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????

    public enum ChatListType {
        // ?????? ?????? ?????? ??????
        SINGLE, LIVE, COURSE, DEVICE
    }

    // ???????????????
    public interface MessageEventListener {
        // ????????????????????????????????????
        void onEmptyTouch();

        void onTipMessageClick(ChatMessage message);

        /**
         * @param message ???????????????????????????????????????????????????
         */
        default void onReplayClick(ChatMessage message) {
        }

        void onMyAvatarClick();

        void onFriendAvatarClick(String friendUserId);

        void LongAvatarClick(ChatMessage chatMessage);

        void onNickNameClick(String friendUserId);

        void onMessageClick(ChatMessage chatMessage);

        void onMessageLongClick(ChatMessage chatMessage);

        void onSendAgain(ChatMessage chatMessage);

        void onMessageBack(ChatMessage chatMessage, int position);

        default void onMessageReplay(ChatMessage chatMessage) {
        }

        void onCallListener(int type);
    }

    // ???????????????
    public class ChatContentAdapter extends BaseAdapter implements ChatHolderListener {

        public int getCount() {
            if (mChatMessages != null) {
                return mChatMessages.size();
            }
            return 0;
        }

        @Override
        public ChatMessage getItem(int position) {
            return mChatMessages.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return ChatHolderFactory.viewholderCount();
        }

        @Override
        public int getItemViewType(int position) {
            ChatMessage message = getItem(position);
            int messageType = message.getType();

            // ??????????????? mySend
            boolean mySend = message.isMySend() || mLoginUser.getUserId().equals(message.getFromUserId());

            // ??????????????????
            if (mySend
                    && !TextUtils.isEmpty(message.getToUserId())
                    && message.getToUserId().contains(mLoginUser.getUserId())
                    && !TextUtils.isEmpty(message.getFromId())) {
                isDevice = true;
                if (message.getFromId().equals(EMConnectionManager.CURRENT_DEVICE)) {
                    mySend = true;
                } else {
                    mySend = false;
                }
            } else {
                isDevice = false;
            }

            // ???????????????
            ChatHolderType holderType = ChatHolderFactory.getChatHolderType(mySend, message);
            if (mCurChatType == ChatListType.LIVE) {
                holderType = ChatHolderType.VIEW_SYSTEM_LIVE;
            }

            return holderType.ordinal();
        }

        public View createHolder(ChatHolderType holderType, View conver, ViewGroup parent) {
            AChatHolderInterface holder = ChatHolderFactory.getHolder(holderType);

            conver = mInflater.inflate(holder.getLayoutId(holder.isMysend), parent, false);
            holder.mContext = mContext;
            holder.mLoginUserId = mLoginUser.getUserId();
            holder.mLoginNickName = mRoomNickName;
            holder.mToUserId = mToUserId;
            holder.isGounp = isGroupChat();
            holder.isDevice = mCurChatType == ChatListType.DEVICE;
            isShowReadPerson = PreferenceUtils.getBoolean(mContext, Constants.IS_SHOW_READ + mToUserId, false);
            holder.setShowPerson(isShowReadPerson);

            holder.findView(conver);
            holder.addChatHolderListener(this);
            conver.setTag(holder);

            return conver;
        }

        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            ChatMessage message = getItem(position);
            // message??????isMySend??????????????????????????????????????????????????????????????????????????????getItemViewType????????????userId?????????
            boolean mySend = message.isMySend() || mLoginUser.getUserId().equals(message.getFromUserId());
            message.setMySend(mySend);
            ChatHolderType holderType = ChatHolderFactory.getChatHolderType(getItemViewType(position));

            AChatHolderInterface holder;
            if (convertView == null) {
                convertView = createHolder(holderType, convertView, parent);
                holder = (AChatHolderInterface) convertView.getTag();
            } else {
                holder = (AChatHolderInterface) convertView.getTag();
                if (holder.mHolderType != holderType) {
                    convertView = createHolder(holderType, convertView, parent);
                    holder = (AChatHolderInterface) convertView.getTag();
                }
            }

            holder.chatMessages = mChatMessages;
            holder.selfGroupRole = mGroupLevel;
            holder.mHolderType = holderType;
            holder.isDevice = isDevice;
            holder.position = position;
            holder.setMultiple(isShowMoreSelect);
            isShowReadPerson = PreferenceUtils.getBoolean(mContext, Constants.IS_SHOW_READ + mToUserId, false);
            holder.setShowPerson(isShowReadPerson);

            // ????????????
            changeTimeVisible(holder, message);
            // ????????????????????????????????????????????????
            changeNameRemark(holder, message);

            // todo ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????listView ????????????????????????
            if (!TextUtils.isEmpty(message.getContent()) && !message.isDecrypted()) {
                String key = SecureChatUtil.getSymmetricKey(message.getPacketId());
                try {
                    // ??????????????????????????????????????????????????????????????????????????????????????????=?????????aes??????????????????????????????????????????Exception?????????content?????????????????????????????????
                    String filter = message.getContent();
                    if (!TextUtils.isEmpty(filter.replaceAll("=", ""))) {
                        String s = AES.decryptStringFromBase64(message.getContent(), Base64.decode(key));
                        message.setContent(s);
                        message.setDecrypted(true);
                    } else {// ???=?????????-??????
                        message.setDecrypted(true);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            holder.prepare(message, memberMap.get(message.getFromUserId()), secret);

            if (holder.mHolderType == ChatHolderType.VIEW_TO_VOICE) {
                // ???????????????????????????
                if (!isGroupChat() && message.getIsReadDel()) {
                    if (!TextUtils.isEmpty(message.getFilePath()) && !mFireVoiceMaps.containsKey(message.getFilePath())) {
                        mFireVoiceMaps.put(message.getFilePath(), message.getPacketId());
                    }
                }

                // ???????????????????????????
                if (!message.isSendRead()) {
                    aVoice.put((VoiceViewHolder) holder);
                }
            }

            if (holder.mHolderType == ChatHolderType.VIEW_TO_TEXT
                    || holder.mHolderType == ChatHolderType.VIEW_TO_REPLAY) {// ??????????????????????????????????????????????????????????????? ??????????????????
                // ???????????????????????????????????????
                TextViewHolder textViewHolder = null;
                TextReplayViewHolder textReplayViewHolder = null;
                if (holder.mHolderType == ChatHolderType.VIEW_TO_TEXT) {
                    textViewHolder = (TextViewHolder) holder;
                    textViewHolder.showFireTime(mTextBurningMaps.containsKey(message.getPacketId()));
                } else {
                    textReplayViewHolder = (TextReplayViewHolder) holder;
                    textReplayViewHolder.showFireTime(mTextBurningMaps.containsKey(message.getPacketId()));
                }

                // ???????????????
                if (!isGroupChat() && message.getIsReadDel() && message.isSendRead()) {
                    if (holder.mHolderType == ChatHolderType.VIEW_TO_TEXT) {
                        textViewHolder.showFireTime(true);
                    } else {
                        textReplayViewHolder.showFireTime(true);
                    }
                    long time = message.getReadTime();
                    if (mTextBurningMaps.containsKey(message.getPacketId())) {
                        CountDownTimer mCountDownTimer = mTextBurningMaps.get(message.getPacketId());
                        mCountDownTimer.cancel();// ?????????????????????
                        mTextBurningMaps.remove(message.getPacketId());
                    }
                    startCountDownTimer(time, holder, message);
                }
            }

            // ??????????????????????????????
            convertView.setAlpha(1f);
            if (mDeletedChatMessageId.contains(message.getPacketId())) {
                startRemoveAnim(convertView, message, position);
            }

            return convertView;
        }

        private void changeTimeVisible(AChatHolderInterface holder, ChatMessage message) {
            int position = holder.position;
            // ?????????????????????????????????????????????5????????????????????????????????????
            String timeStr = null;
            if (position >= 1) {
                long last = mChatMessages.get(position - 1).getTimeSend();
                if (message.getTimeSend() - last > 5 * 60 * 1000) {// ??????5??????????????????
                    timeStr = TimeUtils.sk_time_long_to_chat_time_str(message.getTimeSend());
                }
            }
            holder.showTime(timeStr);
        }

        private void changeNameRemark(AChatHolderInterface holder, ChatMessage message) {
            if (!isGroupChat() || message.isMySend()) {
                return;
            }

            if (mGroupLevel == 1) {// ??????????????????????????????????????????????????????
                RoomMember member = RoomMemberDao.getInstance().getSingleRoomMember(mRoomId, message.getFromUserId());
                if (member != null
                        && !TextUtils.isEmpty(member.getCardName())
                        && !TextUtils.equals(member.getUserName(), member.getCardName())) {// ??????????????????
                    String name = member.getCardName();
                    message.setFromUserName(name);
                    return;
                }
            }
            if (mRemarksMap.containsKey(message.getFromUserId())) {
                message.setFromUserName(mRemarksMap.get(message.getFromUserId()));
            }
        }

        public void clickRootItme(AChatHolderInterface holder, ChatMessage message) {
            mCurClickPos = holder.position;

            // ???????????????????????????????????? ???????????????????????????????????????????????? ???????????????
            if (!isGroupChat() && message.getIsReadDel() && !message.isSendRead()) {
                // ???????????????????????????????????????????????????????????????????????????
                if (holder.mHolderType == ChatHolderType.VIEW_TO_TEXT
                        || holder.mHolderType == ChatHolderType.VIEW_TO_REPLAY) {
                    // ??????chatactivity ???????????????????????????
                    EventBus.getDefault().post(new MessageEventClickFire("delay", message.getPacketId()));
                    // ???????????????????????????
                    clickFireText(holder, message);
                } else if (holder.mHolderType == ChatHolderType.VIEW_TO_VIDEO) {// ???????????????????????????
                    // ??????chatactivity ???????????????????????????
                    EventBus.getDefault().post(new MessageEventClickFire("delay", message.getPacketId()));
                } else if (holder.mHolderType == ChatHolderType.VIEW_TO_IMAGE) {// ???????????????????????????
                    // ??????chatactivity ???????????????????????????
                    EventBus.getDefault().post(new MessageEventClickFire("delay", message.getPacketId()));
                } else if (holder.mHolderType == ChatHolderType.VIEW_TO_VOICE) {// ???????????????????????????
                    // ??????chatactivity ???????????????????????????
                    EventBus.getDefault().post(new MessageEventClickFire("delay", message.getPacketId()));
                }
            }

            if (holder.mHolderType == ChatHolderType.VIEW_FROM_MEDIA_CALL || holder.mHolderType == ChatHolderType.VIEW_TO_MEDIA_CALL) {
                if (mMessageEventListener != null) {
                    mMessageEventListener.onCallListener(message.getType());
                }
                return;
            }

            holder.sendReadMessage(message);
        }

        @Override
        public void onItemClick(View v, AChatHolderInterface holder, ChatMessage message) {
            Log.e("xuan", "onItemClick: " + holder.position);
            if (isShowMoreSelect) {
                if (message.getIsReadDel()) {
                    if (v.getId() == R.id.chat_msc) {
                        holder.setBoxSelect(false);
                    }
                    ToastUtil.showToast(mContext, mContext.getString(R.string.tip_cannot_multi_select_burn));
                    return;
                }
                message.isMoreSelected = !message.isMoreSelected;
                holder.setBoxSelect(message.isMoreSelected);
                return;
            }
            if (message.getType() == TYPE_TEXT) {
                if (clickHistoryMap.get(message.getPacketId()) != null) {
                    //noinspection ConstantConditions
                    if (System.currentTimeMillis() - clickHistoryMap.get(message.getPacketId()) <= 600) {// ??????????????????????????????????????????600ms
                        MessageRemindActivity.start(getContext(), message.toJsonString(), isGroupChat, mToUserId);
                        clickHistoryMap.clear();
                    } else {
                        clickHistoryMap.put(message.getPacketId(), System.currentTimeMillis());
                    }
                } else {
                    clickHistoryMap.put(message.getPacketId(), System.currentTimeMillis());
                }
            }
            switch (v.getId()) {
                case R.id.tv_read: // ????????????????????????
                    Intent intent = new Intent(mContext, RoomReadListActivity.class);
                    intent.putExtra("packetId", message.getPacketId());
                    intent.putExtra("roomId", mToUserId);
                    mContext.startActivity(intent);
                    break;
                case R.id.iv_failed: // ??????????????????????????????????????????
                    holder.mIvFailed.setVisibility(GONE);
                    holder.mSendingBar.setVisibility(VISIBLE);
                    message.setMessageState(ChatMessageListener.MESSAGE_SEND_ING);
                    mMessageEventListener.onSendAgain(message);
                    break;
                case R.id.chat_head_iv: // ???????????????
                    if (message.isMySend()) {
                        mMessageEventListener.onFriendAvatarClick(mLoginUser.getUserId());
                    } else {
                        mMessageEventListener.onFriendAvatarClick(message.getFromUserId());
                    }
                    break;
                case R.id.chat_warp_view:
                    clickRootItme(holder, message);
                    break;
            }

            if (holder.mHolderType == ChatHolderType.VIEW_SYSTEM_TIP) {
                if (mMessageEventListener != null) {
                    mMessageEventListener.onTipMessageClick(message);
                }
                return;
            }
        }

        @Override
        public void onItemLongClick(View v, AChatHolderInterface holder, ChatMessage message) {
            if (mCurChatType == ChatListType.LIVE) {
                return;
            }

            if (isShowMoreSelect) {
                return;
            }

            // ??????????????????
            if (isGroupChat() && v.getId() == R.id.chat_head_iv) {
                mMessageEventListener.LongAvatarClick(message);
                return;
            }

            /**
             * ???????????????window
             */
            mChatPpWindow = new ChatTextClickPpWindow(mContext, new ClickListener(message, holder.position),
                    message, mToUserId, mCurChatType == ChatListType.COURSE, isGroupChat(),
                    mCurChatType == ChatListType.DEVICE, mGroupLevel);
            int offSetX = holder.mouseX - mChatPpWindow.getWidth() / 2;
            // mChatBottomView??????????????????????????????Edit?????????
            int offSetY;
            if (mChatBottomView == null) {// ???????????????mChatBottomView
                offSetY = 0 - (v.getHeight() - holder.mouseY) - DisplayUtil.dip2px(mContext, 12);// ???????????????12dp
            } else {
                offSetY = 0 - (v.getHeight() - holder.mouseY) - mChatBottomView.getmChatEdit().getHeight()
                        - DisplayUtil.dip2px(mContext, 12);// ???????????????12dp
            }
            mChatPpWindow.showAsDropDown(v, offSetX, offSetY);
            /**
             * ???????????????window
             */
/*
            mChatPpWindow = new ChatTextClickPpVerticalWindow(mContext, new ClickListener(message, holder.position),
                    message, mToUserId, mCurChatType == ChatListType.COURSE, isGroupChat(),
                    mCurChatType == ChatListType.DEVICE, mGroupLevel);

            View windowContentViewRoot = mChatPpWindow.getMenuView();
            int[] windowPos = ScreenUtil.calculatePopWindowPos(v, windowContentViewRoot);
            int xOff;// ??????????????????????????????
            if (holder.isMysend) {
                // ?????????????????????????????????????????? + ???????????? + ??????(????????????????????????+??????????????????????????????)
                xOff = v.getWidth() / 2 + holder.mIvHead.getWidth() + ScreenUtil.dip2px(mContext, 12);
            } else {
                // // ????????????????????????????????? - ?????????????????? - ???????????? /2
                xOff = ScreenUtil.getScreenWidth(mContext)
                        - (v.getWidth() + holder.mIvHead.getWidth() + ScreenUtil.dip2px(mContext, 12))
                        - v.getWidth() / 2;
            }
            windowPos[0] -= xOff;
            mChatPpWindow.showAtLocation(v, Gravity.TOP | Gravity.START, windowPos[0], windowPos[1]);
*/
        }

        /**
         * ????????????????????????popupWindow??????????????????????????????onItemLongClick???????????????????????????event???????????????????????????
         *
         * @param v
         * @param event
         * @param holder
         * @param message
         */
        @Override
        public void onItemLongClick(View v, MotionEvent event, AChatHolderInterface holder, ChatMessage message) {
            if (mCurChatType == ChatListType.LIVE) {
                return;
            }

            if (isShowMoreSelect) {
                return;
            }
            if (isGroupChat() && v.getId() == R.id.chat_head_iv) {
                mMessageEventListener.LongAvatarClick(message);
                return;
            }
            mChatPpWindow = new ChatTextClickPpWindow(mContext, new ClickListener(message, holder.position),
                    message, mToUserId, mCurChatType == ChatListType.COURSE, isGroupChat(),
                    mCurChatType == ChatListType.DEVICE, mGroupLevel);
            int offSetX = holder.mouseX - mChatPpWindow.getWidth() / 2;
            mChatPpWindow.showAtLocation(mChatPpWindow.getContentView(), Gravity.NO_GRAVITY, offSetX, (int) event.getRawY());
        }

        @Override
        public void onChangeInputText(String text) {
            if (mChatBottomView != null) {
                mChatBottomView.getmChatEdit().setText(text);
            }
        }

        @Override
        public void onCompDownVoice(ChatMessage message) {
            if (!isGroupChat() && message.getType() == TYPE_VOICE && !message.isMySend()) {
                if (message.getIsReadDel() && !TextUtils.isEmpty(message.getFilePath()) && !mFireVoiceMaps.containsKey(message.getFilePath())) {
                    mFireVoiceMaps.put(message.getFilePath(), message.getPacketId());
                }
            }
        }

        @Override
        public void onReplayClick(View v, AChatHolderInterface holder, ChatMessage message) {
            Log.e("xuan", "onReplayClick: " + holder.position);
            if (isShowMoreSelect) {
                message.isMoreSelected = !message.isMoreSelected;
                holder.setBoxSelect(message.isMoreSelected);
                return;
            }

            ChatMessage replayMessage = new ChatMessage(message.getObjectId());
            int index = -1;
            for (int i = 0; i < mChatMessages.size(); i++) {
                ChatMessage m = mChatMessages.get(i);
                if (TextUtils.equals(m.getPacketId(), replayMessage.getPacketId())) {
                    index = i;
                    break;
                }
            }
            if (index != -1) {
                // ????????????????????????????????????????????????
                smoothScrollToPosition(index);
            } else {
                // ?????????????????????????????????????????????
                if (mMessageEventListener != null) {
                    mMessageEventListener.onReplayClick(message);
                }
            }
        }
    }

    // ????????????????????????
    public class VoicePlayListener implements VoiceManager.VoicePlayListener {
        @Override
        public void onFinishPlay(String path) {
            VoiceViewHolder holder = aVoice.next(mCurClickPos, mChatMessages);
            if (holder != null) {
                mCurClickPos = holder.position;
                ChatMessage message = mChatMessages.get(mCurClickPos);
                holder.sendReadMessage(message);
                // todo ??????ui????????????
                com.sk.weichat.audio_x.VoicePlayer.instance().playVoice(holder.voiceView);
                // VoicePlayer.instance().playVoice(holder.av_chat, message.getTimeLen(), holder.view_audio, false);
                if (message.getIsReadDel()) {
                    EventBus.getDefault().post(new MessageEventClickFire("delay", message.getPacketId()));
                }
            }

            if (mFireVoiceMaps.containsKey(path)) {
                EventBus.getDefault().post(new MessageEventClickFire("delete", mFireVoiceMaps.get(path)));
                mFireVoiceMaps.remove(path);
            }
        }

        @Override
        public void onStopPlay(String path) {
            aVoice.remove(mCurClickPos);
            if (mFireVoiceMaps.containsKey(path)) {
                EventBus.getDefault().post(new MessageEventClickFire("delete", mFireVoiceMaps.get(path)));
                mFireVoiceMaps.remove(path);
            }
        }

        @Override
        public void onErrorPlay() {
        }
    }

    // ???????????????????????? ?????????
    public class AutoVoiceModule {
        HashMap<Integer, VoiceViewHolder> data = new HashMap<>();
        HashMap<VoiceViewHolder, Integer> last = new HashMap<>();

        // ????????????
        public void put(VoiceViewHolder key) {
            if (last.containsKey(key)) {
                int index = last.get(key);
                data.remove(index);
                last.put(key, key.position);
                data.put(key.position, key);
            } else {
                last.put(key, key.position);
                data.put(key.position, key);
            }
        }

        // ????????????
        public VoiceViewHolder next(int position, List<ChatMessage> list) {
            if (position + 1 >= list.size()) {
                return null;
            }

            for (int i = position + 1; i < list.size(); i++) {
                ChatMessage message = list.get(i);
                if (message.getType() == TYPE_VOICE && !message.isMySend() && !message.isSendRead()) {
                    if (data.containsKey(i)) {
                        return data.get(i);
                    }
                }
            }
            return null;
        }

        // ????????????
        public void remove(int position) {
            if (data.containsKey(position)) {
                last.remove(data.get(position));
                data.remove(position);
            }
        }
    }

    // ?????????????????????????????????
    public class ClickListener implements OnClickListener {
        private ChatMessage message;
        private int position;

        public ClickListener(ChatMessage message, int position) {
            this.message = message;
            this.position = position;
        }

        @TargetApi(Build.VERSION_CODES.HONEYCOMB)
        @Override
        public void onClick(View v) {
            mChatPpWindow.dismiss();
            switch (v.getId()) {
                case R.id.item_chat_copy_tv:
                    // ??????
                    if (message.getIsReadDel()) {
                        Toast.makeText(mContext, R.string.tip_cannot_copy_burn, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String s = StringUtils.replaceSpecialChar(message.getContent());
                    CharSequence charSequence = HtmlUtils.transform200SpanString(s, true);
                    // ????????????????????????,??????????????????
                    ClipboardManager cmb = (ClipboardManager) mContext.getSystemService(Context.CLIPBOARD_SERVICE);
                    cmb.setText(charSequence);
                    break;
                case R.id.item_chat_relay_tv:
                    // ????????????
                    if (message.getIsReadDel()) {
                        // ?????????????????????????????????????????????
                        Toast.makeText(mContext, mContext.getString(R.string.cannot_forwarded), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Intent intent = new Intent(mContext, InstantMessageActivity.class);
                    intent.putExtra("fromUserId", mToUserId);
                    intent.putExtra("messageId", message.getPacketId());
                    mContext.startActivity(intent);
                    ((Activity) mContext).finish();
                    break;
                case R.id.item_chat_collection_tv:
                    // ???????????????
                    if (message.getIsReadDel()) {
                        Toast.makeText(mContext, R.string.tip_cannot_save_burn_image, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    collectionEmotion(message, false, isGroupChat, mToUserId);
                    break;
                case R.id.collection_other:
                    // ??????
                    if (message.getIsReadDel()) {
                        Toast.makeText(mContext, R.string.tip_cannot_collect_burn, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!TextUtils.isEmpty(message.getSignature())) {
                        Toast.makeText(mContext, R.string.secure_msg_not_support_collection, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    collectionEmotion(message, true, isGroupChat, mToUserId);
                    break;
                case R.id.item_chat_back_tv:
                    // ????????????
                    mMessageEventListener.onMessageBack(message, position);
                    break;
                case R.id.item_chat_replay_tv:
                    // ????????????
                    mMessageEventListener.onMessageReplay(message);
                    break;
                case R.id.item_chat_del_tv:
                    // ??????
                    if (mCurChatType == ChatListType.COURSE) {
                        if (mMessageEventListener != null) {
                            mMessageEventListener.onMessageClick(message);
                        }
                    } else {
                        // ???????????????????????????
                        Intent broadcast = new Intent(Constants.CHAT_MESSAGE_DELETE_ACTION);
                        broadcast.putExtra(Constants.CHAT_REMOVE_MESSAGE_POSITION, position);
                        mContext.sendBroadcast(broadcast);
                    }
                    break;
                case R.id.item_chat_more_select:
                    // ??????
                    Intent showIntent = new Intent(Constants.SHOW_MORE_SELECT_MENU);
                    showIntent.putExtra(Constants.CHAT_SHOW_MESSAGE_POSITION, position);
                    mContext.sendBroadcast(showIntent);
                    break;
                default:
                    break;
            }
        }
    }
}
