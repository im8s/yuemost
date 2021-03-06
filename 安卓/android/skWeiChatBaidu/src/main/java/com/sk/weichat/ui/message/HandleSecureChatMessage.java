package com.sk.weichat.ui.message;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.sk.weichat.MyApplication;
import com.sk.weichat.R;
import com.sk.weichat.bean.EventSecureNotify;
import com.sk.weichat.bean.Friend;
import com.sk.weichat.bean.PublicKey;
import com.sk.weichat.bean.PublicKeyServer;
import com.sk.weichat.bean.event.MessageSendChat;
import com.sk.weichat.bean.message.ChatMessage;
import com.sk.weichat.bean.message.MucRoom;
import com.sk.weichat.bean.message.XmppMessage;
import com.sk.weichat.broadcast.MsgBroadcast;
import com.sk.weichat.db.dao.ChatMessageDao;
import com.sk.weichat.db.dao.FriendDao;
import com.sk.weichat.db.dao.PublicKeyDao;
import com.sk.weichat.ui.base.CoreManager;
import com.sk.weichat.util.Base64;
import com.sk.weichat.util.TimeUtils;
import com.sk.weichat.util.secure.AES;
import com.sk.weichat.util.secure.DH;
import com.sk.weichat.util.secure.RSA;
import com.sk.weichat.util.secure.chat.SecureChatUtil;
import com.sk.weichat.xmpp.ListenerManager;
import com.xuan.xuanhttplibrary.okhttp.HttpUtils;
import com.xuan.xuanhttplibrary.okhttp.callback.BaseCallback;
import com.xuan.xuanhttplibrary.okhttp.result.ObjectResult;
import com.xuan.xuanhttplibrary.okhttp.result.Result;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import de.greenrobot.event.EventBus;
import okhttp3.Call;

public class HandleSecureChatMessage {
    private static Map<String, List<ChatMessage>> verifySignatureFailedMsgMap = new HashMap<>();
    private static Map<String, Boolean> getUserPublicKeyListFromServerFlagMap = new HashMap<>();

    /**
     * @param chatMessage
     */
    public static void distributionChatMessage(ChatMessage chatMessage) {
        if (chatMessage.getType() == XmppMessage.TYPE_SECURE_REFRESH_KEY) {
            Friend friend = FriendDao.getInstance().getFriend(CoreManager.requireSelf(MyApplication.getContext()).getUserId(), chatMessage.getFromUserId());
            String[] split = chatMessage.getContent().split(",");
            if (friend != null && split.length >= 2) {
                Log.e("msg", "distributionChatMessage success");
                FriendDao.getInstance().updatePublicKeyDH(friend.getUserId(), split[0]);
                FriendDao.getInstance().updatePublicKeyRSARoom(friend.getUserId(), split[1]);
            }
        } else if (chatMessage.getType() == XmppMessage.TYPE_SECURE_SEND_KEY) {
            Friend friend = FriendDao.getInstance().getFriend(CoreManager.requireSelf(MyApplication.getContext()).getUserId(), chatMessage.getObjectId());
            if (friend != null) {
                updateSelfChatKeyGroup(friend.getRoomId(), chatMessage.getContent());
            } else {
                Log.e("msg", "distributionChatMessage failed");
            }
        } else if (chatMessage.getType() == XmppMessage.TYPE_SECURE_NOTIFY_REFRESH_KEY) {// ????????????chatKey
            Friend friend = FriendDao.getInstance().getFriend(CoreManager.requireSelf(MyApplication.getContext()).getUserId(), chatMessage.getObjectId());
            // 1.???????????????????????????????????????????????????????????????????????????????????????????????????????????????
            verifySignatureFailedMsgMap.remove(friend.getUserId());
            ChatMessageDao.getInstance().deleteMessageTable(CoreManager.requireSelf(MyApplication.getContext()).getUserId(), friend.getUserId());
            EventBus.getDefault().post(new EventSecureNotify(EventSecureNotify.MULTI_SNED_RESET_KEY_MSG, chatMessage));
            // 2.??????????????????
            chatMessage.setType(XmppMessage.TYPE_TIP);
            chatMessage.setContent(MyApplication.getContext().getString(R.string.group_owner_reset_chat_key));
            if (ChatMessageDao.getInstance().saveNewSingleChatMessage(CoreManager.requireSelf(MyApplication.getContext()).getUserId(), chatMessage.getObjectId(), chatMessage)) {
                ListenerManager.getInstance().notifyNewMesssage(CoreManager.requireSelf(MyApplication.getContext()).getUserId(), chatMessage.getObjectId(), chatMessage, true);
            }
            // 3.????????????????????????????????????
            getUserPublicKeyListFromServerFlagMap.put(friend.getUserId(), true);
            getFriendChatKeyFromServe(friend.getUserId(), friend.getRoomId());
        }
    }

    /**
     * ?????????????????????????????????
     *
     * @param chatMessage
     */
    public static void handleVerifySignatureFailedMsg(ChatMessage chatMessage) {
        String userId = CoreManager.requireSelf(MyApplication.getContext()).getUserId();
        Friend friend;
        if (TextUtils.equals(userId, chatMessage.getFromUserId())) {
            friend = FriendDao.getInstance().getFriend(userId, chatMessage.getToUserId());
        } else {
            friend = FriendDao.getInstance().getFriend(userId, chatMessage.getFromUserId());
        }
        if (friend != null) {
            boolean isVerifySignatureSuccess = false;
            List<PublicKey> keys = PublicKeyDao.getInstance().getAllPublicKeys(userId, friend.getUserId());
            for (int i = 0; i < keys.size(); i++) {
                String key = DH.getCommonSecretKeyBase64(SecureChatUtil.getDHPrivateKey(userId), keys.get(i).getPublicKey());
                String realKey = SecureChatUtil.getSingleSymmetricKey(chatMessage.getPacketId(), key);
                // ????????????????????????????????????
                String signature = SecureChatUtil.getSignatureSingle(chatMessage.getFromUserId(), chatMessage.getToUserId(),
                        chatMessage.getIsEncrypt(), chatMessage.getPacketId(), realKey,
                        chatMessage.getContent());
                if (TextUtils.equals(chatMessage.getSignature(), signature)) {
                    Log.e("msg", "msg dao decrypt isEncrypt==3 local keys decrypt success");
                    //  ?????????????????????????????????
                    isVerifySignatureSuccess = true;
                    chatMessage.setContent(AES.decryptStringFromBase64(chatMessage.getContent(), Base64.decode(realKey)));
                    chatMessage.setIsEncrypt(0);
                    break;
                }
            }
            if (!isVerifySignatureSuccess) {
                Log.e("msg", "msg dao decrypt isEncrypt==3 local keys decrypt failed, wait...");
                // ????????????dh????????????????????????????????????????????????????????????????????????map????????????????????????????????????dh??????
                List<ChatMessage> chatMessages = getChatMessages(friend.getUserId());
                chatMessages.add(chatMessage);
                verifySignatureFailedMsgMap.put(friend.getUserId(), chatMessages);
                if (!getUserPublicKeyListFromServerFlagMap.containsKey(friend.getUserId())
                        || !getUserPublicKeyListFromServerFlagMap.get(friend.getUserId())) {// ????????????????????????????????????dh??????????????????????????????????????????????????????????????????
                    Log.e("msg", "msg dao decrypt isEncrypt==3 local keys decrypt failed, start loading");
                    getUserPublicKeyListFromServerFlagMap.put(friend.getUserId(), true);
                    getFriendPublicKeyListFromServe(friend.getUserId());
                } else {
                    Log.e("msg", "msg dao decrypt isEncrypt==3 local keys decrypt failed, loading...");
                }
            }
        } else {
            // todo ?????????????????????????????????????????????????????????????????????
            Log.e("msg", "msg dao decrypt isEncrypt==3 local keys decrypt, but friend ==null");
        }

    }

    private static void getFriendPublicKeyListFromServe(String friendId) {
        String ownerId = CoreManager.requireSelf(MyApplication.getContext()).getUserId();
        HashMap<String, String> params = new HashMap<>();
        params.put("userId", friendId);

        HttpUtils.get().url(CoreManager.requireConfig(MyApplication.getContext()).AUTHKEYS_GET_DHMSG_KEY_LIST)
                .params(params)
                .build()
                .execute(new BaseCallback<PublicKeyServer>(PublicKeyServer.class) {
                    @Override
                    public void onResponse(ObjectResult<PublicKeyServer> result) {
                        if (result.getResultCode() == 1) {
                            List<PublicKeyServer.PublicKeyList> publicKeyServers = result.getData().getPublicKeyList();
                            List<PublicKey> publicKeys = new ArrayList<>();
                            for (int i = 0; i < publicKeyServers.size(); i++) {
                                PublicKey publicKey = new PublicKey();
                                publicKey.setOwnerId(ownerId);
                                publicKey.setUserId(result.getData().getUserId());
                                publicKey.setPublicKey(publicKeyServers.get(i).getKey());
                                publicKey.setKeyCreateTime(publicKeyServers.get(i).getTime());
                                publicKeys.add(publicKey);
                            }
                            Log.e("msg", "msg dao decrypt isEncrypt==3 local keys decrypt--->getFriendPublicKeyListFromServe success, reprocessing");
                            // ??????PublicKey
                            PublicKeyDao.getInstance().refreshPublicKeys(ownerId, result.getData().getUserId(), publicKeys);
                            // ?????????????????????????????????????????????flag
                            getUserPublicKeyListFromServerFlagMap.remove(result.getData().getUserId());
                            // ??????map???????????????????????????????????????????????????????????????chatMessages
                            List<ChatMessage> chatMessages = getChatMessages(result.getData().getUserId());
                            verifySignatureFailedMsgMap.remove(result.getData().getUserId());
                            // ???????????????????????????????????????
                            List<PublicKey> keys = PublicKeyDao.getInstance().getAllPublicKeys(ownerId, result.getData().getUserId());
                            boolean isVerifySignatureSuccessAgain = false;
                            for (int i = 0; i < chatMessages.size(); i++) {
                                ChatMessage chatMessage = chatMessages.get(i);
                                for (int i1 = 0; i1 < keys.size(); i1++) {
                                    String key = DH.getCommonSecretKeyBase64(SecureChatUtil.getDHPrivateKey(ownerId), keys.get(i1).getPublicKey());
                                    String realKey = SecureChatUtil.getSingleSymmetricKey(chatMessage.getPacketId(), key);
                                    // ????????????????????????????????????
                                    String signature = SecureChatUtil.getSignatureSingle(chatMessage.getFromUserId(), chatMessage.getToUserId(),
                                            chatMessage.getIsEncrypt(), chatMessage.getPacketId(), realKey,
                                            chatMessage.getContent());
                                    if (TextUtils.equals(chatMessage.getSignature(), signature)) {
                                        Log.e("msg", "msg dao decrypt isEncrypt==3 local keys decrypt--->getFriendPublicKeyListFromServe???reprocessing success");
                                        isVerifySignatureSuccessAgain = true;
                                        // ????????????????????????????????????????????????content?????????
                                        String content = AES.decryptStringFromBase64(chatMessage.getContent(), Base64.decode(realKey));
                                        String saveContent = AES.encryptBase64(content, Base64.decode(SecureChatUtil.getSymmetricKey(chatMessage.getPacketId())));
                                        chatMessage.setContent(saveContent);
                                        chatMessage.setIsEncrypt(0);
                                        // ??????????????????????????????????????????????????????????????????
                                        ChatMessageDao.getInstance().updateVerifySignatureFailedMsg(ownerId, result.getData().getUserId(),
                                                chatMessage.getPacketId(), saveContent);
                                        break;
                                    } else {
                                        // todo ?????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????[????????????????????????????????????]
                                    }
                                }
                            }
                            if (isVerifySignatureSuccessAgain) {
                                // ?????????????????????????????????????????????????????????????????????????????????????????????????????????
                                Log.e("msg", "msg dao decrypt isEncrypt==3 local keys decrypt--->getFriendPublicKeyListFromServe???isVerifySignatureSuccessAgain,notify");
                                MsgBroadcast.broadcastMsgUiUpdate(MyApplication.getContext());
                            }
                        } else {
                            Log.e("msg", "msg dao decrypt isEncrypt==3 local keys decrypt--->getFriendPublicKeyListFromServe???" + result.getResultMsg());
                            // getUserPublicKeyListFromServerFlagMap.remove(result.getData().getUserId());
                        }
                    }

                    @Override
                    public void onError(Call call, Exception e) {
                        Log.e("msg", "msg dao decrypt isEncrypt==3 local keys decrypt--->getFriendPublicKeyListFromServe???onError");
                        getUserPublicKeyListFromServerFlagMap.put(friendId, false);
                    }
                });
    }

    /**
     * ?????????????????????????????????
     *
     * @param chatMessage
     */
    public static void handleVerifySignatureFailedMsgGroup(ChatMessage chatMessage) {
        String userId = CoreManager.requireSelf(MyApplication.getContext()).getUserId();
        Friend friend = FriendDao.getInstance().getFriend(userId, chatMessage.getToUserId());
        if (friend != null) {
            // ??????????????????chatKey????????????????????????????????????????????????
            List<ChatMessage> chatMessages = getChatMessages(friend.getUserId());
            chatMessages.add(chatMessage);
            verifySignatureFailedMsgMap.put(friend.getUserId(), chatMessages);
            if (!getUserPublicKeyListFromServerFlagMap.containsKey(friend.getUserId())
                    || !getUserPublicKeyListFromServerFlagMap.get(friend.getUserId())) {// ????????????????????????????????????dh??????????????????????????????????????????????????????????????????
                Log.e("msg", "msg dao decrypt isEncrypt==3 local keys decrypt failed, start loading");
                getUserPublicKeyListFromServerFlagMap.put(friend.getUserId(), true);
                getFriendChatKeyFromServe(friend.getUserId(), friend.getRoomId());
            } else {
                Log.e("msg", "msg dao decrypt isEncrypt==3 local keys decrypt failed, loading...");
            }
        } else {
            // todo ?????????????????????????????????????????????????????????????????????
            Log.e("msg", "msg dao decrypt isEncrypt==3 local keys decrypt, but friend ==null");
        }
    }

    /**
     * ???????????????????????????????????????
     *
     * @param roomId
     */
    private static void getFriendChatKeyFromServe(String friendId, String roomId) {
        String ownerId = CoreManager.requireSelf(MyApplication.getContext()).getUserId();
        HashMap<String, String> params = new HashMap<>();
        params.put("roomId", roomId);

        HttpUtils.get().url(CoreManager.requireConfig(MyApplication.getContext()).ROOM_GET_ROOM)
                .params(params)
                .build()
                .execute(new BaseCallback<MucRoom>(MucRoom.class) {
                    @Override
                    public void onResponse(ObjectResult<MucRoom> result) {
                        if (result.getResultCode() == 1) {
                            Log.e("msg", "msg dao decrypt isEncrypt==3 local keys decrypt--->getFriendChatKeyFromServe success, reprocessing");
                            MucRoom mucRoom = result.getData();
                            boolean isUpdate = false;
                            if (mucRoom.getIsSecretGroup() == 1 && mucRoom.getMember() != null) {
                                try {
                                    String chatKey = new String(RSA.decryptFromBase64(mucRoom.getMember().getChatKeyGroup(), Base64.decode(SecureChatUtil.getRSAPrivateKey(ownerId))));
                                    FriendDao.getInstance().updateChatKeyGroup(mucRoom.getJid(), SecureChatUtil.encryptChatKey(mucRoom.getJid(), chatKey));
                                    isUpdate = true;
                                    Log.e("msg", "??????chatKey??????-->" + chatKey);
                                } catch (Exception e) {
                                    Log.e("msg", "??????chatKey??????");
                                    FriendDao.getInstance().updateIsLostChatKeyGroup(mucRoom.getJid(), 1);
                                }
                            }
                            // ?????????????????????????????????????????????flag
                            getUserPublicKeyListFromServerFlagMap.remove(friendId);
                            if (isUpdate) {
                                // ??????map???????????????????????????????????????????????????????????????chatMessages
                                List<ChatMessage> chatMessages = getChatMessages(friendId);
                                verifySignatureFailedMsgMap.remove(friendId);
                                Friend friend = FriendDao.getInstance().getFriend(ownerId, friendId);
                                boolean isVerifySignatureSuccessAgain = false;
                                for (int i = 0; i < chatMessages.size(); i++) {
                                    ChatMessage chatMessage = chatMessages.get(i);
                                    String key = SecureChatUtil.decryptChatKey(chatMessage.getToUserId(), friend.getChatKeyGroup());
                                    String realKey = SecureChatUtil.getSingleSymmetricKey(chatMessage.getPacketId(), key);
                                    // ????????????????????????????????????
                                    String signature = SecureChatUtil.getSignatureMulti(chatMessage.getFromUserId(), chatMessage.getToUserId(),
                                            chatMessage.getIsEncrypt(), chatMessage.getPacketId(), realKey,
                                            chatMessage.getContent());
                                    // ??????????????????
                                    if (TextUtils.equals(chatMessage.getSignature(), signature)) {
                                        Log.e("msg", "msg dao decrypt isEncrypt==3 local keys decrypt--->getFriendPublicKeyListFromServe???reprocessing success");
                                        isVerifySignatureSuccessAgain = true;
                                        // ????????????????????????????????????????????????content?????????
                                        String content = AES.decryptStringFromBase64(chatMessage.getContent(), Base64.decode(realKey));
                                        String saveContent = AES.encryptBase64(content, Base64.decode(SecureChatUtil.getSymmetricKey(chatMessage.getPacketId())));
                                        chatMessage.setContent(saveContent);
                                        chatMessage.setIsEncrypt(0);
                                        // ??????????????????????????????????????????????????????????????????
                                        ChatMessageDao.getInstance().updateVerifySignatureFailedMsg(ownerId, friendId,
                                                chatMessage.getPacketId(), saveContent);
                                    } else {
                                        Log.e("msg", "msg dao decrypt isEncrypt==3 local keys decrypt--->getFriendPublicKeyListFromServe???reprocessing fail");
                                        // todo ?????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????[????????????????????????????????????]
                                    }
                                }
                                if (isVerifySignatureSuccessAgain) {
                                    // ?????????????????????????????????????????????????????????????????????????????????????????????????????????
                                    Log.e("msg", "msg dao decrypt isEncrypt==3 local keys decrypt--->getFriendPublicKeyListFromServe???isVerifySignatureSuccessAgain,notify");
                                    MsgBroadcast.broadcastMsgUiUpdate(MyApplication.getContext());
                                }
                            }
                        } else {
                            Log.e("msg", "msg dao decrypt isEncrypt==3 local keys decrypt--->getFriendChatKeyFromServe???" + result.getResultMsg());
                            getUserPublicKeyListFromServerFlagMap.remove(friendId);
                        }
                    }

                    @Override
                    public void onError(Call call, Exception e) {
                        Log.e("msg", "msg dao decrypt isEncrypt==3 local keys decrypt--->getFriendChatKeyFromServe???onError");
                        getUserPublicKeyListFromServerFlagMap.put(friendId, false);
                    }
                });
    }

    private static List<ChatMessage> getChatMessages(String friendId) {
        List<ChatMessage> chatMessages = verifySignatureFailedMsgMap.get(friendId);
        if (chatMessages == null) {
            chatMessages = new ArrayList<>();
        }
        return chatMessages;
    }

    /**
     * ???????????????isLostChatKeyGroup???????????????804????????????chatKeyGroup
     *
     * @param isDelay: ????????????????????????????????????delay?????????
     * @param roomJid
     */
    public static void sendRequestChatKeyGroupMessage(boolean isDelay, String roomJid) {
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setType(XmppMessage.TYPE_SECURE_LOST_KEY);
        chatMessage.setFromUserId(CoreManager.requireSelf(MyApplication.getContext()).getUserId());
        chatMessage.setFromUserName(CoreManager.requireSelf(MyApplication.getContext()).getNickName());
        chatMessage.setToUserId(roomJid);
        String signature = RSA.signBase64(roomJid,
                Base64.decode(SecureChatUtil.getRSAPrivateKey(CoreManager.requireSelf(MyApplication.getContext()).getUserId())));
        chatMessage.setContent(signature);
        chatMessage.setObjectId(roomJid);
        chatMessage.setPacketId(UUID.randomUUID().toString().replaceAll("-", ""));
        chatMessage.setTimeSend(TimeUtils.sk_time_current_time());
        if (isDelay) {
            Handler handler = new Handler(Looper.getMainLooper());// ??????????????????handler???????????????prepare Looper || ?????????????????????Looper
            handler.postDelayed(() -> EventBus.getDefault().post(new MessageSendChat(true, roomJid, chatMessage)), 1000);
        } else {
            EventBus.getDefault().post(new MessageSendChat(true, roomJid, chatMessage));
        }
    }

    /**
     * click 804 msg, send 805 msg for requested member
     *
     * @param chatMessage
     */
    public static void sendChatKeyForRequestedMember(ChatMessage chatMessage) {
        // ??????????????????????????????key?????????rsa??????
        Friend friend = FriendDao.getInstance().getFriend(CoreManager.requireSelf(MyApplication.getContext()).getUserId(), chatMessage.getToUserId());
        HashMap<String, String> params = new HashMap<>();
        params.put("roomId", friend.getRoomId());
        params.put("userId", chatMessage.getFromUserId());

        HttpUtils.get().url(CoreManager.requireConfig(MyApplication.getContext()).ROOM_GET_MEMBER_RSA_PUBLIC_KEY)
                .params(params)
                .build()
                .execute(new BaseCallback<String>(String.class) {
                    @Override
                    public void onResponse(ObjectResult<String> result) {
                        if (Result.checkSuccess(MyApplication.getContext(), result)) {
                            // ???????????????????????????????????????
                            try {
                                JSONObject jsonObject = new JSONObject(result.getData());
                                String publicKey = jsonObject.getString("rsaPublicKey");
                                if (RSA.verifyFromBase64(chatMessage.getObjectId(), Base64.decode(publicKey), chatMessage.getContent())) {
                                    // ???????????????????????????805???????????????????????????????????????805???????????????????????????????????????????????????
                                    EventBus.getDefault().post(new MessageSendChat(false, chatMessage.getFromUserId(),
                                            createMessage(chatMessage, friend.getChatKeyGroup(), publicKey, false)));
                                    EventBus.getDefault().post(new MessageSendChat(true, chatMessage.getToUserId(),
                                            createMessage(chatMessage, friend.getChatKeyGroup(), publicKey, true)));
                                }
                            } catch (JSONException e) {
                                e.printStackTrace();
                            }
                        }
                    }

                    @Override
                    public void onError(Call call, Exception e) {

                    }
                });
    }

    private static ChatMessage createMessage(ChatMessage message, String chatKeyGroup, String publicKey,
                                             boolean isGroup) {
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setType(XmppMessage.TYPE_SECURE_SEND_KEY);
        chatMessage.setFromUserId(CoreManager.requireSelf(MyApplication.getContext()).getUserId());
        chatMessage.setFromUserName(CoreManager.requireSelf(MyApplication.getContext()).getNickName());
        chatMessage.setToUserId(isGroup ? message.getToUserId() : message.getFromUserId());
        if (isGroup) {
            // ??????????????????msgId
            chatMessage.setContent(message.getPacketId());
        } else {
            // ????????????????????????chaKey
            String chatKey = SecureChatUtil.decryptChatKey(message.getToUserId(), chatKeyGroup);
            chatMessage.setContent(RSA.encryptBase64(chatKey.getBytes(), Base64.decode(publicKey)));
        }
        chatMessage.setGroup(isGroup);
        chatMessage.setObjectId(message.getToUserId());
        chatMessage.setPacketId(UUID.randomUUID().toString().replaceAll("-", ""));
        chatMessage.setTimeSend(TimeUtils.sk_time_current_time());
        return chatMessage;
    }

    /**
     * ???????????????chatKey
     *
     * @param roomId
     */
    private static void updateSelfChatKeyGroup(String roomId, String key) {
        HashMap<String, String> params = new HashMap<>();
        params.put("roomId", roomId);
        params.put("key", key);

        HttpUtils.get().url(CoreManager.requireConfig(MyApplication.getContext()).UPDETE_GROUP_CHAT_KEY)
                .params(params)
                .build()
                .execute(new BaseCallback<Void>(Void.class) {
                    @Override
                    public void onResponse(ObjectResult<Void> result) {

                    }

                    @Override
                    public void onError(Call call, Exception e) {
                    }
                });
    }
}
