package com.shiku.im.dao.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.mongodb.*;
import com.mongodb.MongoClient;
import com.mongodb.client.*;
import com.shiku.common.model.PageResult;
import com.shiku.common.util.StringUtils;
import com.shiku.commons.thread.ThreadUtils;
import com.shiku.im.comm.constants.KConstants;
import com.shiku.im.comm.constants.MsgType;
import com.shiku.im.comm.ex.ServiceException;
import com.shiku.im.comm.utils.DateUtil;
import com.shiku.im.comm.utils.StringUtil;
import com.shiku.im.constants.DBConstants;
import com.shiku.im.message.MessageType;
import com.shiku.im.message.dao.TigaseMsgDao;
import com.shiku.im.repository.MongoOperator;
import com.shiku.im.repository.MongoRepository;
import com.shiku.im.user.service.UserCoreService;
import com.shiku.im.utils.ConstantUtil;
import com.shiku.im.utils.SKBeanUtils;
import com.shiku.mongodb.springdata.MongoConfig;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.stream.Collectors;


/**
 *
 * @version V1.0
 * @Description: TODO(todo)
 * @date 2019/9/3 19:27
 */
@Repository
public class TigaseMsgDaoImpl extends MongoRepository<Object,Integer> implements TigaseMsgDao , InitializingBean {

    private Logger lastChatLog = LoggerFactory.getLogger("lastChatLog");



    @Autowired(required=false)
    @Qualifier(value = "imRoomMongoClient")
    private MongoClient imRoomMongoClient;

    @Autowired(required=false)
    private MongoConfig mongoConfig;


    private MongoDatabase chatMsgDB;

    public MongoDatabase getChatMsgDB() {
        return chatMsgDB;
    }

    private MongoDatabase offlineDB;

    public MongoDatabase getOfflineDB() {
        return offlineDB;
    }

    @Override
    public MongoTemplate getDatastore() {
        return SKBeanUtils.getDatastore();
    }

    @Override
    public Class<Object> getEntityClass() {
        return Object.class;
    }

    private MongoDatabase lastMsgDB;

    public MongoDatabase getLastMsgDB() {
        return lastMsgDB;
    }

    private MongoDatabase imRoomDB;

    public MongoDatabase getImRoomDB() {
        return imRoomDB;
    }

    @Autowired(required=false)
    private UserCoreService userCoreService;

    private static final String  CHATMSG_DBNAME = "shiku_msgs";


    public final  String MUCMSG="mucmsg_";

    protected static final int DB_REMAINDER=10000;


    @Override
    public String getCollectionName(int userId) {
        int remainder=0;
        if(userId> KConstants.MIN_USERID) {
            remainder=userId/DB_REMAINDER;
        }
        return String.valueOf(remainder);
    }
    @Override
    public MongoCollection<Document> getMongoCollection(MongoDatabase database,int userId) {
        int remainder=0;
        if(userId>KConstants.MIN_USERID) {
            remainder=userId/DB_REMAINDER;
        }
        return database.getCollection(String.valueOf(remainder));
    }


    @Override
    public void afterPropertiesSet() throws Exception {
        if(null!=imRoomMongoClient) {
            chatMsgDB = imRoomMongoClient.getDatabase(DBConstants.CHAT_MSGS_DB);
            lastMsgDB = imRoomMongoClient.getDatabase(DBConstants.LASTCHAT_DB);
            offlineDB = imRoomMongoClient.getDatabase(DBConstants.OFFLINE_CHAT_DB);
            String roomDbName = mongoConfig.getRoomDbName();
            imRoomDB = imRoomMongoClient.getDatabase(roomDbName);
        }

    }
    

    @Override
    public Document getLastBody(int sender, int receiver) {
        MongoCollection<Document> dbCollection =getMsgRepostory(sender);
        Document q = new Document();
        q.put("sender", sender+"");
        q.put("receiver", receiver+"");
        Document dbObj = dbCollection.find(q).sort(new Document("ts",-1)).first();
        if(null==dbObj) {
            return null;
        }
        return dbObj;
    }

    public MongoCollection<Document> getMsgRepostory(int sender) {
		return chatMsgDB.getCollection(getCollectionName(sender));
	}

    @Override
    public List<Object> getMsgList(int userId, int pageIndex, int pageSize) {
        List<Object> msgList = Lists.newArrayList();
        MongoCollection<Document> dbCollection = getMsgRepostory(userId);
        // ????????????
        Document groupFileds =new Document();
        groupFileds.put("sender", "$sender");
        // ????????????
        Document map =new Document();
        map.put("receiver", userId+"");
        map.put("direction", 0);
        map.put("isRead", 0);
        Document macth=new Document("$match",new Document(map));

        Document fileds = new Document("_id", groupFileds);
        fileds.put("count", new BasicDBObject("$sum",1));
        Document group = new Document("$group", fileds);
        Document limit=new Document("$limit",pageSize);
        Document skip=new Document("$skip",pageIndex*pageSize);
        AggregateIterable<Document> out= dbCollection.aggregate(Arrays.asList(macth,group,skip,limit));
        MongoCursor<Document> iterator=out.iterator();

        try {
            while (iterator.hasNext()){
                Document document=iterator.next();
                Document dbObj=(Document) document.get("_id");
                dbObj.append("count", document.get("count"));
                int sender = dbObj.getInteger("sender");
                int receiver = userId;
                String nickname="";
                if(null!= userCoreService.getUser(sender))
                    nickname = userCoreService.getUser(sender).getNickname();
                else
                    continue;
                int count = dbObj.getInteger("count");

                dbObj.put("nickname", nickname);
                dbObj.put("count", count);
                dbObj.put("sender", sender+"");
                dbObj.put("receiver", receiver+"");
                Document lastBody = getLastBody(sender, receiver);
                dbObj.put("body", lastBody.get("content"));
                JSONObject body = JSONObject.parseObject(lastBody.getString("message"));
                if (null != body.get("isEncrypt") && true==body.getBoolean("isEncrypt")) {
                    dbObj.put("isEncrypt", 1);
                } else {
                    dbObj.put("isEncrypt", 0);
                }
                dbObj.put("messageId", lastBody.get("messageId"));
                dbObj.put("timeSend", lastBody.get("timeSend"));
                msgList.add(dbObj);
            }
        }catch (Exception e){
            e.printStackTrace();
        }finally {
            iterator.close();
        }


        return msgList;
    }

    @Override
    public Object getMsgList(int sender, int receiver, int pageIndex, int pageSize) {
        List<Document> msgList = Lists.newArrayList();
        Document q = new Document();
        q.put("sender", sender+"");
        q.put("receiver", receiver+"");
        q.put("direction", 0);
        q.put("isRead", 0);
        MongoCollection<Document> dbCollection = getMsgRepostory(sender);
        MongoCursor<Document> cursor = dbCollection.find(q).iterator();
        while (cursor.hasNext()) {
            Document dbObj = cursor.next();
            dbObj.put("nickname", userCoreService.getUser(sender).getNickname());
            dbObj.put("content",dbObj.get("content").toString());
            // ??????body
            JSONObject body = JSONObject.parseObject(dbObj.getString("message"));
            if (null != body.get("isEncrypt") && "1".equals(body.get("isEncrypt").toString())) {
                dbObj.put("isEncrypt", 1);
            } else {
                dbObj.put("isEncrypt", 0);
            }
            dbObj.put("timeSend", dbObj.get("timeSend"));
            //System.out.println("dbobj : "+JSONObject.toJSONString(dbObj));
            msgList.add(dbObj);
        }
        return msgList;
    }

    @Override
    public void updateMsgIsReadStatus(int userId, String msgId) {
        Document query = new Document("messageId",msgId);
        getMsgRepostory(userId).
                updateOne(query, new Document("$set",new Document("isRead", 1)));
    }

    @Override
    public void deleteLastMsg(String userId, String jid) {
        MongoCollection<Document> collection = getMongoCollection(lastMsgDB,Integer.valueOf(userId));
        Document query=new Document("jid", jid);
        if(!StringUtil.isEmpty(userId)) {
            query.append("userId", userId);
        }
        collection.deleteMany(query);
        lastChatLog.info("???????????????????????? =====> query "+JSONObject.toJSONString(query) +" ???????????? "+ StringUtils.getCurrentMethod()+" ?????????????????? "+ StringUtils.getMethoedPath());
    }



    @Override
    public void deleteUserAllLastMsg(String userId) {

        MongoCollection<Document> collection = null;

        Document query = new Document();
        List<Document> orQuery = new ArrayList<>();
        orQuery.add(new Document("userId",userId));
        orQuery.add(new Document("jid",userId));
        query.append(MongoOperator.OR,orQuery);

        MongoIterable<String> listCollectionNames = getLastMsgDB().listCollectionNames();
        MongoCollection<Document> dbCollection = null;
        for (String collectionName : listCollectionNames) {
            dbCollection = getLastMsgDB().getCollection(collectionName);
            dbCollection.deleteMany(query);
            //deleteByQuery(query,collectionName);
        }
        lastChatLog.info("???????????????????????? =====> query "+JSONObject.toJSONString(query) +" ???????????? "+ StringUtils.getCurrentMethod()+" ?????????????????? "+StringUtils.getMethoedPath());
    }

    /**
     * ????????????????????????
     * @return
     */
    @Override
    public long getMsgCountNum() {
        long count=0;
        MongoIterable<String> listCollectionNames = getChatMsgDB().listCollectionNames();
        for (String string : listCollectionNames) {
            count+=getChatMsgDB().getCollection(string).count();
        }

        return count;
    }

    /**
     * ????????????????????????      ????????????  ???????????????????????????????????????
     * @param startDate
     * @param endDate
     * @param counType  ????????????   1: ??????????????????      2:???????????????       3.???????????????   4.?????????????????? (??????)
     */
    @Override
    public List<Object> getChatMsgCount(String startDate, String endDate, int counType){


        List<Object> countData;

        long startTime = 0; //?????????????????????
        long endTime = 0; //?????????????????????,?????????????????????

        /**
         * ??????????????????????????????????????????????????????????????????????????? ; ????????????????????????????????????????????????????????????????????????;
         * ??????????????????????????????????????????????????????????????????0???
         */
        long defStartTime = counType==4? com.shiku.utils.DateUtil.getTodayMorning().getTime()/1000
                : counType==3 ? com.shiku.utils.DateUtil.getLastMonth().getTime()/1000 : com.shiku.im.comm.utils.DateUtil.getLastYear().getTime()/1000;


        startTime = StringUtil.isEmpty(startDate) ? defStartTime :DateUtil.toDate(startDate).getTime();
        endTime = StringUtil.isEmpty(endDate) ? System.currentTimeMillis() : DateUtil.toDate(endDate).getTime();

        Document queryTime = new Document("$ne",null);

        if(startTime!=0 && endTime!=0){
            queryTime.append("$gt", startTime);
            queryTime.append("$lt", endTime);
        }

        Document query = new Document("ts",queryTime);



        String mapStr = "function Map() { "
                + "var date = new Date(this.ts);"
                +  "var year = date.getFullYear();"
                +  "var month = (\"0\" + (date.getMonth()+1)).slice(-2);"  //month ???0?????????????????????1
                +  "var day = (\"0\" + date.getDate()).slice(-2);"
                +  "var hour = (\"0\" + date.getHours()).slice(-2);"
                +  "var minute = (\"0\" + date.getMinutes()).slice(-2);"
                +  "var dateStr = date.getFullYear()"+"+'-'+"+"(parseInt(date.getMonth())+1)"+"+'-'+"+"date.getDate();";

        if(counType==1){ // counType=1: ??????????????????
            mapStr += "var key= year + '-'+ month;";
        }else if(counType==2){ // counType=2:???????????????
            mapStr += "var key= year + '-'+ month + '-' + day;";
        }else if(counType==3){ //counType=3 :???????????????
            mapStr += "var key= year + '-'+ month + '-' + day + '  ' + hour +' : 00';";
        }else if(counType==4){ //counType=4 :??????????????????
            mapStr += "var key= year + '-'+ month + '-' + day + '  ' + hour + ':'+ minute;";
        }

        mapStr += "emit(key,1);}";

        String reduce = "function Reduce(key, values) {" +
                "return Array.sum(values);" +
                "}";

        Map<String,Double> map = new HashMap<String,Double>();
        //??????????????????????????????
        MongoIterable<String> collectionNames = getChatMsgDB().listCollectionNames();
        for (String str : collectionNames) {
            MongoCollection collection = getChatMsgDB().getCollection(str,Document.class);
            if(0==collection.count(query))
                continue;
            MongoCursor iterator = collection.mapReduce(mapStr, reduce, Document.class).iterator();
            Double value=null;
            String id=null;
            while (iterator.hasNext()) {
                Document obj = (Document) iterator.next();
                id=(String)obj.get("_id");
                value= (Double)obj.get("value");
                if(null!=map.get(id)) {
                    map.put(id,map.get(id)+value);
                }else {
                    map.put(id,value);
                }

                //System.out.println("====>>>>????????????"+JSON.toJSON(obj));
            }
        }
        countData=map.entrySet().stream().collect(Collectors.toList());
        map.clear();

        return countData;
    }

    /**
     * ????????????????????????      ????????????  ???????????????????????????????????????
     * @param startDate
     * @param endDate
     * @param counType  ????????????   1: ??????????????????      2:???????????????       3.???????????????   4.?????????????????? (??????)
     */
    @Override
    public List<Object> getGroupMsgCount(String roomId,String startDate, String endDate, short counType){

        //??????????????????????????????
        MongoCollection<Document> collection =getImRoomDB().getCollection(MUCMSG+roomId);

        if(collection==null || collection.count()==0){
            System.out.println("????????????");
            throw new ServiceException("????????????");
        }

        List<Object> countData = new ArrayList<Object>();

        long startTime = 0; //?????????????????????

        long endTime = 0; //?????????????????????,?????????????????????

        /**
         * ??????????????????????????????????????????????????????????????????????????? ; ????????????????????????????????????????????????????????????????????????;
         * ??????????????????????????????????????????????????????????????????0???
         */
        long defStartTime = counType==4? DateUtil.getTodayMorning().getTime()
                : counType==3 ? DateUtil.getLastMonth().getTime(): DateUtil.getLastYear().getTime();

        startTime = StringUtil.isEmpty(startDate) ? defStartTime :DateUtil.toDate(startDate).getTime();
        endTime = StringUtil.isEmpty(endDate) ? System.currentTimeMillis() : DateUtil.toDate(endDate).getTime();

        Document queryTime = new Document("$ne",null);

        if(startTime!=0 && endTime!=0){
            queryTime.append("$gt", startTime);
            queryTime.append("$lt", endTime);
        }

        Document query = new Document("ts",queryTime);



        String mapStr = "function Map() { "
                + "var date = new Date(this.ts);"
                +  "var year = date.getFullYear();"
                +  "var month = (\"0\" + (date.getMonth()+1)).slice(-2);"  //month ???0?????????????????????1
                +  "var day = (\"0\" + date.getDate()).slice(-2);"
                +  "var hour = (\"0\" + date.getHours()).slice(-2);"
                +  "var minute = (\"0\" + date.getMinutes()).slice(-2);"
                +  "var dateStr = date.getFullYear()"+"+'-'+"+"(parseInt(date.getMonth())+1)"+"+'-'+"+"date.getDate();";

        if(counType==1){ // counType=1: ??????????????????
            mapStr += "var key= year + '-'+ month;";
        }else if(counType==2){ // counType=2:???????????????
            mapStr += "var key= year + '-'+ month + '-' + day;";
        }else if(counType==3){ //counType=3 :???????????????
            mapStr += "var key= year + '-'+ month + '-' + day + '  ' + hour +' : 00';";
        }else if(counType==4){ //counType=4 :??????????????????
            mapStr += "var key= year + '-'+ month + '-' + day + '  ' + hour + ':'+ minute;";
        }

        mapStr += "emit(key,1);}";

        String reduce = "function Reduce(key, values) {" +
                "return Array.sum(values);" +
                "}";
        MapReduceIterable<Document> mapReduceIterable = collection.mapReduce(mapStr, reduce);
        mapReduceIterable.filter(query);
        MongoCursor<Document> iterator = mapReduceIterable.iterator();

        Map<String,Double> map = new HashMap<String,Double>();
        while (iterator.hasNext()) {
            Document obj =  iterator.next();

            map.put((String)obj.get("_id"),(Double)obj.get("value"));
            countData.add(JSON.toJSON(map));
            map.clear();
            //System.out.println("======>>>??????????????? "+JSON.toJSON(obj));

        }

        return countData;
    }

    @Override
    public void cleanUserFriendHistoryMsg(int userId,String serverName) {
        MongoCollection<Document> collection = getCollection(lastMsgDB,userId);
        Document query=new Document("userId", userId+"");
        collection.deleteMany(query);
        lastChatLog.info("???????????????????????? =====> query "+JSONObject.toJSONString(query) +" ???????????? "+ StringUtils.getCurrentMethod()+" ?????????????????? "+StringUtils.getMethoedPath());
        MongoCollection<Document> msgRepostory = getMsgRepostory(userId);
        query=new Document("sender", userId+"");
        msgRepostory.deleteMany(query);
        // ???????????????????????????(??????serverName)
        MongoCollection<Document> msgHistory = getOfflineDB().getCollection(getCollectionName(userId));
        query=new Document("to", userId+"");
        msgHistory.deleteMany(query);


    }

    public void destroyUserMsgRecord(int userId){
        ThreadUtils.executeInThread(obj -> {
            DBCursor cursor = null;
            MongoCursor<String> mongoCursor=null;
            MongoCollection<Document> dbCollection=getMsgRepostory(userId);
            MongoCollection<Document> lastdbCollection;
            try{

                lastdbCollection =getCollection(lastMsgDB,userId);
                Document query = new Document();
                Document lastquery=new Document();
                query.append("sender", userId+"");
                query.append("deleteTime", new Document(MongoOperator.GT,0)
                        .append(MongoOperator.LT, DateUtil.currentTimeSeconds()))
                        .append("isRead", 1);

                Document base=dbCollection.find(query).first();
                List<Document> queryOr = new ArrayList<>();
                if(base!=null){
                    queryOr.add(new Document("jid", String.valueOf(base.get("sender"))).append("userId", base.get("receiver").toString()));
                    queryOr.add(new Document("userId",String.valueOf(base.get("sender"))).append("jid", base.get("receiver").toString()));
                    lastquery.append(MongoOperator.OR, queryOr);
                }else {
                    return;
                }
                 // ????????????
                query.append("contentType",new Document(MongoOperator.IN, MsgType.FileTypeArr));
                 mongoCursor = dbCollection.distinct("content", query, String.class).iterator();

                while (mongoCursor.hasNext()) {
                    try {
                        ConstantUtil.deleteFile(mongoCursor.next());
                    }catch (Exception e){
                        e.printStackTrace();
                    }

                }

                query.remove("contentType");

                dbCollection.deleteMany(query); //?????????????????????????????????

                // ????????????????????????????????????
                List<Document> baslist = new ArrayList<>();
                if(base!=null){
                    baslist.add(new Document("receiver", base.get("sender")));
                    baslist.add(new Document("sender", base.get("sender")));
                    query.append(MongoOperator.OR,baslist);
                }

                query.remove("sender");
                query.remove("deleteTime");
                query.remove("isRead");
                Document lastMsgObj=dbCollection.find(query).sort(new Document("timeSend", -1)).first();

                if(lastMsgObj!=null){
                    Document values=new Document();
                    values.put("messageId", lastMsgObj.get("messageId"));
                    values.put("timeSend",new Double(lastMsgObj.get("timeSend").toString()).longValue());
                    values.put("content", lastMsgObj.get("content"));
                    if(!lastquery.isEmpty()){
                        lastdbCollection.updateMany(lastquery,new Document(MongoOperator.SET, values));
                        lastChatLog.info("?????????????????????????????? =====> query "+JSONObject.toJSONString(lastquery)+" ???????????? "+ StringUtils.getCurrentMethod()+" ?????????????????? "+StringUtils.getMethoedPath());
                    }

                }else{
                   return;
                }

                if(!lastquery.isEmpty()){
                    lastdbCollection.deleteMany(lastquery);
                    lastChatLog.info("???????????????????????? =====> query "+JSONObject.toJSONString(query) +" ???????????? "+ StringUtils.getCurrentMethod()+" ?????????????????? "+StringUtils.getMethoedPath());
                }

            } catch (Exception e){
                e.printStackTrace();
            }finally {
                if(cursor != null) {
                    cursor.close();
                }
                if(null!=mongoCursor) {
                    mongoCursor.close();
                }
            }
        });
    }

    @Override
    public void cleanFriendMessage(int userId, int toUserId, int type) {
        // ?????????????????????
        MongoCollection<Document> dbCollection = null;
        // ????????????????????????
        MongoCollection<Document> lastdbCollection = null;
        List<Document> queryOr = new ArrayList<>();
        try {

            dbCollection = getMsgRepostory(userId);
            lastdbCollection =getMongoCollection(lastMsgDB,userId);

            BasicDBObject queryAll = new BasicDBObject();

            BasicDBObject lastqueryAll = new BasicDBObject();
            if (type == 1) {
                queryAll.append("sender", userId+"");

                /*
                 * queryAll.append("contentType", new BasicDBObject(MongoOperator.IN,
                 * MsgType.FileTypeArr)); List<String> fileList=dbCollection.distinct("content",
                 * queryAll); for(String fileUrl:fileList){ // ?????????????????????????????????????????????
                 * ConstantUtil.deleteFile(fileUrl); } queryAll.remove("contentType");
                 */

                Document baseAll =  dbCollection.find(queryAll).first();

                if(null!=baseAll){
                        /*queryOr.add(new Document("userId", String.valueOf(baseAll.get("sender"))).append("jid",
                            baseAll.get("receiver").toString()));*/
                    queryOr.add(new Document("userId", String.valueOf(baseAll.get("sender"))).append("jid",
                            baseAll.get("receiver").toString()));
                    queryOr.add(new Document("jid", String.valueOf(baseAll.get("sender"))).append("userId", baseAll.get("receiver").toString()));



                    lastqueryAll.append(MongoOperator.OR, queryOr);
                }

                lastdbCollection.deleteMany(lastqueryAll);
                lastChatLog.info("???????????????????????? =====> query "+JSONObject.toJSONString(lastqueryAll) +" ???????????? "+ StringUtils.getCurrentMethod()+" ?????????????????? "+StringUtils.getMethoedPath());
                dbCollection.deleteMany(queryAll);
                return;
            }
            BasicDBObject query = new BasicDBObject();
            BasicDBObject lastquery = new BasicDBObject();
            query.append("sender", userId+"");
            if (0 != toUserId) {
                query.append("receiver", toUserId+"");
            }

            /*
             * query.append("contentType", new BasicDBObject(MongoOperator.IN,
             * MsgType.FileTypeArr)); List<String> fileList=dbCollection.distinct("content",
             * query); for(String fileUrl:fileList){ // ?????????????????????????????????????????????
             * ConstantUtil.deleteFile(fileUrl); } query.remove("contentType");
             */
            // ???????????????????????????
            Document base =  dbCollection.find(query).first();
            if (base != null) {
                lastquery.append("userId", String.valueOf(base.get("sender"))).append("jid",
                        base.get("receiver").toString());
            }else {
                return;
            }


            // ??????????????????
            dbCollection.deleteMany(query);
            lastdbCollection.deleteMany(lastquery);
            lastChatLog.info("???????????????????????? =====> query "+JSONObject.toJSONString(lastquery) +" ???????????? "+ StringUtils.getCurrentMethod()+" ?????????????????? "+StringUtils.getMethoedPath());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public Document queryCollectMessage(int userId,String roomJid, String messageId) {
        MongoCollection<Document> dbCollection = null;
        Document data = null;
        if (StringUtil.isEmpty(roomJid)) {
            dbCollection = getMsgRepostory(userId);
        } else {
            dbCollection = getImRoomDB().getCollection(MUCMSG + roomJid);
        }
        Document query = new Document();
        query.put("messageId", messageId);
       // Document  project= new Document("message",0);
        data =  dbCollection.find(query).first();
        //log.info(" emojiMsg  ?????????"+JSONObject.toJSONString(data));
        return data;
    }

    @Override
    public Document queryMessage(int userId,String roomJid, String messageId) {
        MongoCollection<Document> dbCollection = null;
        Document data = null;
        if (StringUtil.isEmpty(roomJid)) {
            dbCollection = getMsgRepostory(userId);
        } else {
            dbCollection = getImRoomDB().getCollection(MUCMSG + roomJid);
        }
        Document query = new Document();
        query.put("messageId", messageId);
        // Document  project= new Document("message",0);
        data =  dbCollection.find(query).first();
        //log.info(" emojiMsg  ?????????"+JSONObject.toJSONString(data));
        return data;
    }

    @Override
    public List<Document> queryMsgDocument(int userId,String roomJid, List<String> messageIds) {
        List<Document> result=new ArrayList<>();
        MongoCollection<Document> dbCollection=null;
        if(("0").equals(roomJid)){
            dbCollection = getMsgRepostory(userId);
        }else{
            dbCollection = getImRoomDB().getCollection(MUCMSG+roomJid);
        }
        Document q = new Document();
        q.put("messageId",new BasicDBObject(MongoOperator.IN,messageIds));
        q.put("sender", userId+"");
       // Document  project= new Document("message",0);
        MongoCursor<Document> dbCursor = dbCollection.find(q).iterator();
//				CourseMessage courseMessage=new CourseMessage();
        while (dbCursor.hasNext()) {
            result.add(dbCursor.next());
//					courseMessage.setCourseMessageId(new ObjectId());
//					courseMessage.setUserId(course.getUserId());
//					courseMessage.setCourseId(course.getCourseId().toString());
//					courseMessage.setCreateTime(String.valueOf(dbObj.get("timeSend")));
//					courseMessage.setMessage(String.valueOf(dbObj));
//					courseMessage.setMessageId(String.valueOf(dbObj.get("messageId")));
//					getUserDao().saveEntity(courseMessage);

        }
        dbCursor.close();
        return result;
    }


    /**
     * ????????????????????????????????????
     * @param startTime
     * @param endTime
     * @param room_jid_id
     */
    @Override
    public void deleteGroupMsgBytime(long startTime, long endTime,String room_jid_id){
        Document fileQuery=new Document("contentType",new Document(MongoOperator.IN, MsgType.FileTypeArr));
        Document query = new Document();
        if (0 != startTime) {
            query.put("ts", new Document("$gte", startTime));
        }
        if (0 != endTime) {
            query.put("ts", new Document("$gte", endTime));
        }
        MongoCollection<Document> dbCollection=null;
        if (room_jid_id != null) {
             dbCollection = getImRoomDB().getCollection(MUCMSG + room_jid_id);

            MongoCursor<Document> iterator = dbCollection.find(fileQuery).projection(new Document("content",1)).iterator();
            while (iterator.hasNext()) {

                Document dbObj = iterator.next();
                // ???????????????

                try {
                    // ?????????????????????????????????????????????
                    ConstantUtil.deleteFile(dbObj.getString("content"));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            // ?????????????????????????????????
            dbCollection.deleteMany(query);
        } else {
            MongoCursor<String> jidList = getImRoomDB().getCollection("shiku_room").distinct("jid", String.class).iterator();
            MongoCursor<Document> iterator = null;
            while (jidList.hasNext()) {
                dbCollection = getImRoomDB().getCollection(MUCMSG + jidList.next());
                iterator = dbCollection.find(fileQuery).projection(new Document("content", 1)).iterator();
                while (iterator.hasNext()) {

                    Document dbObj = iterator.next();
                    // ???????????????

                    try {
                        // ?????????????????????????????????????????????
                        ConstantUtil.deleteFile(dbObj.getString("content"));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }


    }

    /**
     * ????????????????????????by uid
     */
    @Override
    public void deleteGroupMsgByUid(int userId, String room_jid_id) {
        if (userId <= 0 || org.apache.commons.lang3.StringUtils.isBlank(room_jid_id)) {
            return;
        }
        Document query = new Document("sender", userId+"");
        MongoCollection<Document> dbCollection = getImRoomDB().getCollection(MUCMSG + room_jid_id);
        // ?????????????????????????????????
        dbCollection.deleteMany(query);
    }

    @Override
    public void deleteOutTimeMucMsg() {
        MongoCollection<Document> dbCollection=null;
        // ????????????????????????
        MongoCollection<Document> lastdbCollection=null;

        Document query = null;
        Document lastquery=null;
        try {
            logger.info("=====> deleteMucMsgRecord "+DateUtil.TimeToStr(new Date()));
            MongoCursor<String> set=getImRoomDB().listCollectionNames().iterator();
            String s=null;
            while (set.hasNext()){
                s=set.next();
                if(s.startsWith("mucmsg_")){
                    lastquery=new Document();
                    query=new Document();
                    query.append("deleteTime", new BasicDBObject(MongoOperator.GT,0)
                            .append(MongoOperator.LT, DateUtil.currentTimeSeconds()));
                    dbCollection =getDatastore().getCollection(s);
                    lastdbCollection =getDatastore().getCollection("shiku_lastChats");

                    Document base= dbCollection.find(query).first();
                    if(base!=null)
                        lastquery.put("jid", base.get("room_jid_id"));
                    /*
                     * if(base!=null) query.put("room_jid_id", base.get("room_jid_id"));
                     */

                    // ????????????
                    query.append("contentType",new Document(MongoOperator.IN, MsgType.FileTypeArr));
                    MongoCursor<String> fileList= dbCollection.distinct("content", query,String.class).iterator();

                    while (fileList.hasNext()) {
                        ConstantUtil.deleteFile(fileList.next());
                    }
                    fileList.close();

                    // ?????????????????????????????????
                    query.remove("contentType");
                    dbCollection.deleteMany(query);

                    query.remove("deleteTime");
                    Document lastMsgObj=dbCollection.find(query).sort(new Document("timeSend", -1)).limit(1).first();
                    Document values=new Document();
                    if (lastMsgObj!=null) {
                        values.put("messageId", lastMsgObj.get("messageId"));
                        values.put("timeSend",new Double(lastMsgObj.get("timeSend").toString()).longValue());
                        values.put("content", lastMsgObj.get("content"));
                        if(!lastquery.isEmpty()){
                            lastdbCollection.updateMany(lastquery,new Document(MongoOperator.SET, values));
                            lastChatLog.info("?????????????????????????????? =====> query "+JSONObject.toJSONString(lastquery)+" ???????????? "+ StringUtils.getCurrentMethod()+" ?????????????????? "+StringUtils.getMethoedPath());
                        }
                    }else{
                        if(!lastquery.isEmpty()){
                            lastdbCollection.deleteMany(lastquery);
                            lastChatLog.info("???????????????????????? =====> query "+JSONObject.toJSONString(lastquery) +" ???????????? "+ StringUtils.getCurrentMethod()+" ?????????????????? "+StringUtils.getMethoedPath());
                        }

                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public List<Document> queryChatMessageRecord(int userId, int toUserId, long startTime, long endTime, int pageIndex, int pageSize, int maxType) {
        List<Document> list = Lists.newArrayList();
        MongoCollection<Document> dbCollection = getMsgRepostory(userId);
        Document q = new Document();
        q.put("sender", userId+"");
        q.put("receiver", toUserId+"");

        if (maxType > 0)
            q.put("contentType", new Document(MongoOperator.LT, maxType));
        if (0 != startTime && 0 != endTime) {
            startTime=startTime*1000;
            endTime=endTime*1000;
            q.put("timeSend", new Document("$gte", startTime).append("$lte", endTime));
        } else if (0 != startTime || 0 != endTime) {
            if (0 != startTime) {
                startTime=startTime*1000;
                q.put("timeSend", new Document("$gte", startTime));
            }else {
                endTime=endTime*1000;
                q.put("timeSend", new Document("$lte", endTime));
            }
        }

        MongoCursor<Document> iterator=null;
        try {
             iterator = dbCollection.find(q).sort(new Document("timeSend", -1)).skip(pageIndex * pageSize)
                    .limit(pageSize).iterator();
            Document next=null;
            boolean isRead=false;
            while (iterator.hasNext()) {
                 next = iterator.next();
                /**
                 * ?????????????????? ?????????????????????
                 */
                /*if(!isRead) {
                   isRead = 1 == next.getInteger("isRead", 0) ? true : false;
                 }else{
                     next.put("isRead",1);
                }*/
                list.add(next);
            }
            return list;
        } catch (Exception e) {
            logger.error(e.getMessage(),e);
            return list;
        } finally {
            if(null!=iterator){
                iterator.close();
            }
        }
    }

    @Override
    public List<Document> queryMucMsgs(String roomJid, long startTime, long endTime, int pageIndex, int pageSize, int maxType,boolean flag) {
        List<Document> list = Lists.newArrayList();
        MongoCollection<Document> dbCollection = getImRoomDB().getCollection(MUCMSG + roomJid);
        Document q = new Document();
        //q.put("room_jid_id", roomJid);
        if (0 != startTime && 0 != endTime) {
            if (flag) {
                startTime = startTime * 1000;
//                endTime = endTime * 1000;
            }
            q.put("timeSend", new Document(MongoOperator.GTE, startTime).append(MongoOperator.LTE, endTime));
        } else if (0 != startTime || 0 != endTime) {
            if (0 != startTime) {
                if (flag)
                    startTime = startTime * 1000;
                q.put("timeSend", new Document(MongoOperator.GTE, startTime));
            } else {
                /*if (flag)
                    endTime = endTime * 1000;*/
                q.put("timeSend", new Document(MongoOperator.LTE, endTime));
            }
        }
        // ????????????????????????????????????????????? contentType=83
        q.put("contentType", new Document(MongoOperator.NE, MessageType.OPENREDPAKET));
        q.put("deleteTime", new Document(MongoOperator.LT, DateUtil.currentTimeSeconds()));
        /*
         * DBObject projection=new BasicDBList(); projection.put("body", 1);
         */
        MongoCursor<Document> iterator = null;
        try {
            iterator = dbCollection.find(q).sort(new Document("timeSend", -1)).skip(pageIndex * pageSize)
                    .limit(pageSize).iterator();
            while (iterator.hasNext()) {
                list.add(iterator.next());
            }
            return list;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return list;
        } finally {
            if (null != iterator) {
                iterator.close();
            }
        }

    }

    /**
     * ?????????????????????????????????
     * @param sender
     * @param receiver
     * @param page
     * @param limit
     * @return
     */
    @Override
    public PageResult<Document> queryFirendMsgRecord(Integer sender, Integer receiver, Integer page, Integer limit){
        MongoCollection<Document> dbCollection = getMsgRepostory(sender);
        Document query = new Document();
        List<Document> queryOr = new ArrayList<>();
        if(0 != sender){
            queryOr.add(new Document("sender",sender.toString()).append("receiver", receiver.toString()).append("direction", 0));
        }
        if(0 != receiver){
            queryOr.add(new Document("sender",receiver.toString()).append("receiver", sender.toString()).append("direction", 0));
        }
        query.append(MongoOperator.OR, queryOr);

        long total = dbCollection.count(query);


        MongoCursor<Document> cursor =null;
        List<Document> pageData = null;
        PageResult<Document> result= null;
        try {
            cursor=dbCollection.find(query).sort(new BasicDBObject("_id", -1)).skip((page-1) * limit).limit(limit).iterator();
            pageData = Lists.newArrayList();
            result = new PageResult<Document>();

            while (cursor.hasNext()) {
                Document dbObj = cursor.next();
                @SuppressWarnings("deprecation")
                JSONObject body = JSONObject.parseObject(dbObj.getString("message"));
                if(null != body.get("isEncrypt") && "1".equals(body.get("isEncrypt").toString())){
                    dbObj.put("isEncrypt", 1);
                }else{
                    dbObj.put("isEncrypt", 0);
                }
                try {
                    dbObj.put("sender_nickname", userCoreService.getNickName(Integer.valueOf(dbObj.getString("sender"))));
                } catch (Exception e) {
                    dbObj.put("sender_nickname", "??????");
                }
                try {
                    dbObj.put("receiver_nickname", userCoreService.getNickName(Integer.valueOf(dbObj.getString("receiver"))));
                } catch (Exception e) {
                    dbObj.put("receiver_nickname", "??????");
                }
                try {
                    dbObj.put("content",dbObj.get("content"));
                } catch (Exception e) {
                    dbObj.put("content", "--");
                }
                pageData.add(dbObj);
            }
        } catch (Exception e) {
            logger.error(e.getMessage(),e);
            return result;
        } finally {
            if(null!=cursor){
                cursor.close();
            }

        }

        result.setData(pageData);
        result.setCount(total);
        return result;
    }

    @Override
    public void deleteMucHistory(){

        //log.info("timeCount  ---> "+(System.currentTimeMillis()-start));
    }

    @Override
    public void dropRoomChatHistory(String roomJid) {
        getImRoomDB().getCollection(MUCMSG+roomJid).drop();
        cleanTigaseMuc_History(roomJid);
        cleanRoomTigase_Nodes(roomJid);

    }

    @Override
    public void cleanTigaseMuc_History(String roomJid) {

    }

    @Override
    public void cleanRoomTigase_Nodes(String roomJid) {
        /*?????? tig_nodes ???????????????*/

    }

    @Override
    public List<Document> queryLastChatList(String userId,long startTime, long endTime, int pageSize, List<String> roomJidList) {

        List<Document> resultList = new ArrayList<>();
        MongoCollection<Document> dbCollection = getMongoCollection(lastMsgDB,Integer.valueOf(userId));
        Document query = new Document();
        if (0 != startTime && 0 != endTime) {
            query.put("timeSend", new Document("$gte", startTime).append("$lte", endTime));
        }
        if (0 != startTime || 0 != endTime) {
            if (0 != startTime) {
                query.put("timeSend", new Document("$gte", startTime));
            } else {
                query.put("timeSend", new Document("$lte", endTime));
            }

        }
        Document sort = new Document("timeSend", -1);
        MongoCursor<Document> cursor = null;
        Document dbObj = null;
        if(null!=roomJidList&&0<roomJidList.size()){
            query.append("jid", new Document(MongoOperator.IN, roomJidList));

            cursor=lastMsgDB.getCollection(DBConstants.LASTCHAT_MUC_COLLECTION).find(query).sort(sort).limit(pageSize).iterator();
            try {
                while (cursor.hasNext()) {
                    dbObj = cursor.next();
                    resultList.add(dbObj);
                }
            } finally {
                if(null!=cursor)
                    cursor.close();
            }


            query.remove("jid");
        }
        query.append("userId", userId).append("isRoom", 0);

        // System.out.println("query ==> "+query.toJson());

        if (0 == pageSize)
            cursor = dbCollection.find(query).sort(sort).iterator();
        else {
            cursor = dbCollection.find(query).sort(sort).skip(0).limit(pageSize).iterator();
        }


        try {
            while (cursor.hasNext()) {
                dbObj = cursor.next();

                /*
                 * if((int)dbObj.get("isRoom")!=1){ // User
                 * user=userManager.getUser((int)cursor.next().get("userId")); User
                 * toUser=userCoreService.getUser(Integer.valueOf((String)dbObj.get("jid")));
                 * if(null==toUser) { continue; }
                 *
                 * dbObj.put("toUserName",toUser.getNickname()); }else{ User
                 * roomIdToUser=userCoreService.getUser(Integer.valueOf((String)dbObj.get(
                 * "userId"))); dbObj.put("toUserName",roomIdToUser.getNickname()); }
                 */

                resultList.add(dbObj);
            }

        } catch (Exception e) {
            logger.error(e.getMessage(),e);
        } finally {
            if(null!=cursor)
                cursor.close();
        }


        return resultList;
    }


    @Override
    public List<Document> queryLastChatList(int userId ,long startTime, int pageSize){
        //Integer userId = ReqUtil.getUserId();
        List<Document> resultList = Lists.newArrayList();

        BasicDBObject query=new BasicDBObject();
        if (0 != startTime)
            query.put("timeSend", new BasicDBObject("$lt", startTime));

        query.append("userId", userId+"");

        Document dbObj=null;

        MongoCursor<Document> cursor = getMongoCollection(lastMsgDB, Integer.valueOf(userId)).find(query)
                .sort(new Document("timeSend", -1)).limit(pageSize).iterator();


        try {
            while (cursor.hasNext()) {
                dbObj = cursor.next();
                if((int)dbObj.get("isRoom")!=1){
                    String nickName = userCoreService.getNickName(Integer.valueOf((String)dbObj.get("jid")));
                    dbObj.put("toUserName",nickName);
                }
                resultList.add(dbObj);
            }
        } finally {
            if(null!=cursor)
                cursor.close();
        }

        return resultList;
    }

    @Override
    public void deleteTimeOutChatMsgRecord(){
        MongoCollection<Document> dbCollection=null;
        MongoCollection<Document> lastdbCollection=null;
        MongoCursor<String> dbNames=null;
        try{
            logger.info("=====> deleteChatMsgRecord "+DateUtil.TimeToStr(new Date()));

            Document query = new Document();

            query.append("deleteTime", new Document(MongoOperator.GT,0)
                    .append(MongoOperator.LT, DateUtil.currentTimeSeconds()))
                    .append("isRead",1);


            dbNames = chatMsgDB.listCollectionNames().iterator();
            String name=null;
            while (dbNames.hasNext()){
                name=dbNames.next();
                dbCollection=chatMsgDB.getCollection(name);
                lastdbCollection=lastMsgDB.getCollection(name);
                deleteTimeOutChatMsgRecord(dbCollection,lastdbCollection,query);
            }


        } catch (Exception e){
           logger.error(e.getMessage(),e);
        }finally {
            if(null!=dbNames){
                dbNames.close();
            }
        }
    }

    public void deleteTimeOutChatMsgRecord( MongoCollection<Document> dbCollection,MongoCollection<Document> lastdbCollection, Document query){
        try{


            Document base=dbCollection.find(query).first();

            Document lastquery=new Document();
            BasicDBList queryOr = new BasicDBList();
            if(base!=null){
                queryOr.add(new BasicDBObject("jid", String.valueOf(base.get("sender"))).append("userId", base.get("receiver").toString()));
                queryOr.add(new BasicDBObject("userId",String.valueOf(base.get("sender"))).append("jid", base.get("receiver").toString()));
                lastquery.append(MongoOperator.OR, queryOr);
            }else {
                return;
            }

            // ????????????
            query.append("contentType",new BasicDBObject(MongoOperator.IN, MsgType.FileTypeArr));
            MongoCursor<String> iterator = dbCollection.distinct("content", query, String.class).iterator();

            while (iterator.hasNext()) {
                ConstantUtil.deleteFile(iterator.next());
            }
            if(null!=iterator)
                iterator.close();
            //?????????????????????????????????
            dbCollection.deleteMany(query);

            query.remove("contentType");


            //?????????????????????????????????
            dbCollection.deleteMany(query);
            query.remove("messageId");
            query.remove("sender");

            // ????????????????????????????????????
            BasicDBList baslist = new BasicDBList();
            if(base!=null){
                baslist.add(new BasicDBObject("receiver", base.get("sender")));
                baslist.add(new BasicDBObject("sender", base.get("sender")));
                query.append(MongoOperator.OR,baslist);
            }
            query.remove("sender");
            query.remove("deleteTime");
            query.remove("isRead");
            Document lastMsgObj=dbCollection.find(query).sort(new BasicDBObject("timeSend", -1)).limit(1).first();

            if(lastMsgObj!=null){
                BasicDBObject values=new BasicDBObject();
                values.put("messageId", lastMsgObj.get("messageId"));
                values.put("timeSend",new Double(lastMsgObj.get("timeSend").toString()).longValue());
                values.put("content", lastMsgObj.get("content"));
                if(!lastquery.isEmpty()){
                    lastdbCollection.updateMany(lastquery,new BasicDBObject(MongoOperator.SET, values));
                    lastChatLog.info("?????????????????????????????? =====> query "+JSONObject.toJSONString(lastquery)+" ???????????? "+ StringUtils.getCurrentMethod()+" ?????????????????? "+StringUtils.getMethoedPath());
                }
            }else{
               /* if(!lastquery.isEmpty()){
                    lastdbCollection.deleteMany(lastquery);
                    lastChatLog.info("???????????????????????? =====> query "+JSONObject.toJSONString(lastquery) +" ???????????? "+ StringUtils.getCurrentMethod()+" ?????????????????? "+StringUtils.getMethoedPath());
                }*/
            }


        } catch (Exception e){
           logger.error(e.getMessage(),e);
        }
    }

    @Override
    public void deleteMsgUpdateLastMessage(final int sender, String roomJid, final String messageId
            , int delete, int type) {
        DBCursor cursor = null;
        MongoCollection<Document> dbCollection;
        try {
            if (type == 1) {
                dbCollection = getMsgRepostory(sender);
            }else {
                dbCollection = getImRoomDB().getCollection(MUCMSG + roomJid);
            }
            // ????????????????????????
            MongoCollection<Document> lastdbCollection = getDatastore().getCollection("shiku_lastChats");
            Document query = new Document();
            if (!StringUtil.isEmpty(messageId)){
                String[] split = messageId.split(",");
                query.put("messageId", new Document(MongoOperator.IN, Arrays.asList(split)));
            }else {
                logger.info(" messageId is null ===>");
                return;
            }


            if (1 == delete) {
                query.put("sender", sender+"");
            }

            /**
             * ?????????????????????????????????????????????
             */


            Document base =  dbCollection.find(query).first();
            Document lastquery = new Document();
            // ?????????????????????????????????
            BasicDBList queryOr = new BasicDBList();
            if (1 == type) {
                if (delete == 1) {
                    lastquery.put("userId", sender+"");
                } else if (delete == 2) {
                    query.append("contentType", new BasicDBObject(MongoOperator.IN, MsgType.FileTypeArr));
                    MongoCursor<String> iterator = dbCollection.distinct("content", query, String.class).iterator();
                    while (iterator.hasNext()) {
                        // ?????????????????????????????????????????????
                        ConstantUtil.deleteFile(iterator.next());
                    }
                    query.remove("contentType");

                    queryOr.add(new BasicDBObject("jid", String.valueOf(base.get("sender"))).append("userId",
                            base.get("receiver").toString()));
                    queryOr.add(new BasicDBObject("userId", String.valueOf(base.get("sender"))).append("jid",
                            base.get("receiver").toString()));
                    lastquery.append(MongoOperator.OR, queryOr);
                }
            } else {
                lastquery.put("jid", base.get("room_jid_id"));
            }

            // ????????????
            BasicDBList baslist = new BasicDBList();
            if (type != 2) {
                baslist.add(new BasicDBObject("receiver", sender+""));
                baslist.add(new BasicDBObject("sender", sender+""));
                query.append(MongoOperator.OR, baslist);
            } else {
                query.put("room_jid_id", base.get("room_jid_id"));
            }

            // ?????????????????????????????????
            dbCollection.deleteMany(query);
            query.remove("messageId");
            query.remove("sender");
            Document lastMsgObj = dbCollection.find(query).sort(new BasicDBObject("timeSend", -1)).limit(1).first();
            Document values = new Document();
            values.put("messageId", lastMsgObj.get("messageId"));
            values.put("timeSend", new Double(lastMsgObj.get("timeSend").toString()).longValue());
            values.put("content", lastMsgObj.get("content"));
            lastdbCollection.updateMany(lastquery, new Document(MongoOperator.SET, values));
            lastChatLog.info("?????????????????????????????? =====> query "+JSONObject.toJSONString(lastquery)+" ???????????? "+ StringUtils.getCurrentMethod()+" ?????????????????? "+StringUtils.getMethoedPath());
        } catch (Exception e) {
            logger.error(e.getMessage(),e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    @Override
    public void changeMsgReadStatus(String messageId, int userId, int toUserId) {
        try {
            MongoCollection<Document> dbCollection = getMsgRepostory(userId);

            Document query = new Document();
            query.put("messageId", messageId);

            Document dbObj =  dbCollection.find(query).first();
            String body = null;
            if (null == dbObj)
                return ;
            else {
                body = dbObj.getString("message");
                if (null == body)
                    return ;
            }
            // ???????????????
            Map<String, Object> msgBody = JSON.parseObject(body);
            msgBody.put("isRead", 1);
            body = JSON.toJSON(msgBody).toString();
            dbCollection.updateMany(query, new Document(MongoOperator.SET, new BasicDBObject("message", body)));

        } catch (Exception e) {
            e.printStackTrace();
        }
    }



    @Override
    public PageResult<Document> chat_logs_all(long startTime, long endTime, int sender,
                                              int receiver, int page, int limit, String keyWord) throws Exception {
        MongoCollection<Document> dbCollection;
        if(0 != sender && 0 != receiver){
            dbCollection = getChatMsgDB().getCollection(getCollectionName(sender));
        }else if (0 == sender && 0 != receiver){
            dbCollection = getChatMsgDB().getCollection(getCollectionName(receiver));
        }else{
            dbCollection = getChatMsgDB().getCollection("1000");
        }
        BasicDBObject q = new BasicDBObject();
        if (0 == receiver) {
            q.put("receiver", new BasicDBObject("$ne", 10005+""));
            q.put("direction", 0);
        } else {
            q.put("direction", 0);
            q.put("receiver", BasicDBObjectBuilder.start("$eq", receiver+"").add("$ne", 10005+"").get());
        }
        if (0 == sender) {
            q.put("sender", new BasicDBObject("$ne", 10005+""));
            q.put("direction", 0);
        } else {
            q.put("direction", 0);
            q.put("sender", BasicDBObjectBuilder.start("$eq", sender+"").add("$ne", 10005+"").get());
        }
        if(!StringUtil.isEmpty(keyWord)){
            q.put("content", new BasicDBObject(MongoOperator.REGEX,keyWord));
        }

        if (0 != startTime)
            q.put("ts", new BasicDBObject("$gte", startTime));
        if (0 != endTime)
            q.put("ts", new BasicDBObject("$lte", endTime));

        long total = dbCollection.count(q);
        List<Document> pageData = Lists.newArrayList();

        MongoCursor<Document> cursor = dbCollection.find(q).sort(new BasicDBObject("_id", -1)).skip((page - 1) * limit).limit(limit).iterator();
        PageResult<Document> result = new PageResult<Document>();
        while (cursor.hasNext()) {
            Document dbObj =  cursor.next();
            JSONObject body = JSONObject.parseObject(dbObj.getString("message"));
            if(null==body)
                continue;
            if (null != body.get("isEncrypt") && true==body.getBoolean("isEncrypt")) {
                dbObj.put("isEncrypt", 1);
            } else {
                dbObj.put("isEncrypt", 0);
            }
            try {
                dbObj.put("sender_nickname", userCoreService.getNickName(Integer.valueOf(dbObj.getString("sender"))));
            } catch (Exception e) {
                dbObj.put("sender_nickname", "??????");
            }
            try {
                dbObj.put("receiver_nickname",userCoreService.getNickName(Integer.valueOf(dbObj.getString("receiver"))));
            } catch (Exception e) {
                dbObj.put("receiver_nickname", "??????");
            }
            try {
                dbObj.put("content",dbObj.get("content"));
            } catch (Exception e) {
                dbObj.put("content", "--");
            }

            pageData.add(dbObj);

        }
        result.setData(pageData);
        result.setCount(total);
        return result;
    }


    @Override
    public void chat_logs_all_del(long startTime, long endTime, int sender,
                                  int receiver, int pageIndex, int pageSize)throws Exception {
       
        MongoCollection<Document> dbCollection = getMsgRepostory(sender);
        Document q = new Document();

        if (0 == sender) {
            q.put("sender", new Document("$ne", 10005));
        } else {
            q.put("sender", BasicDBObjectBuilder.start("$eq", sender).add("$ne", 10005).get());
        }
        if (0 == receiver) {
            q.put("receiver", new BasicDBObject("$ne", 10005));
        } else {
            q.put("receiver", BasicDBObjectBuilder.start("$eq", receiver).add("$ne", 10005).get());
        }
        if (0 != startTime)
            q.put("ts", new Document("$gte", startTime));
        if (0 != endTime)
            q.put("ts", new Document("$lte", endTime));
        dbCollection.deleteMany(q);

    }

    @Override
    public void deleteChatMsgs(String msgId, int type) {
       
        MongoCollection<Document> dbCollection =chatMsgDB.getCollection("1000");
        BasicDBObject q = new BasicDBObject();
        try {
            if (0 == type) {
                String[] msgIds = StringUtil.getStringList(msgId);
                for (String strMsgId : msgIds) {
                        q.put("_id", new ObjectId(strMsgId));
                        dbCollection.deleteMany(q);
                    }
                
            } else if (1 == type) {
                // ?????????????????????????????????
                long onedayNextDay = DateUtil.getOnedayNextDay(DateUtil.currentTimeSeconds(), 30, 1);
                System.out.println("?????????????????????" + onedayNextDay);
                q.put("timeSend", new BasicDBObject("$lte", onedayNextDay));
                dbCollection.deleteMany(q);
            } else if (2 == type) {
                final int num = 100000;
                long count = dbCollection.count();
                if (count <= num)
                    throw new ServiceException("??????????????????" + num);
                // ?????????????????????????????????
                MongoCursor<Document> iterator = dbCollection.find().sort(new BasicDBObject("timeSend", -1)).skip(num).limit((int) count).iterator();
                while (iterator.hasNext()) {
                    dbCollection.deleteMany(iterator.next());
                }
                logger.info("??????" + num + "???????????????" + count);
            }
        } catch (Exception e) {
            throw e;
        }
    }




    @Override
    public PageResult<Document>  groupchat_logs_all(long startTime, long endTime, String room_jid_id,
                                                    int page, int limit, String keyWord) {
        MongoCollection<Document> dbCollection = getImRoomDB().getCollection(MUCMSG + room_jid_id);

        BasicDBObject q = new BasicDBObject();
        if (0 != startTime)
            q.put("ts", new BasicDBObject("$gte", startTime));
        if (0 != endTime)
            q.put("ts", new BasicDBObject("$lte", endTime));
        if(!StringUtil.isEmpty(keyWord))
            q.put("content", new BasicDBObject(MongoOperator.REGEX,keyWord));

        long total = dbCollection.count(q);
        List<Document> pageData = Lists.newArrayList();
        PageResult<Document> result = new PageResult<Document>();
        MongoCursor<Document> cursor = dbCollection.find(q).sort(new BasicDBObject("ts", -1)).skip((page - 1) * limit).limit(limit).iterator();
        while (cursor.hasNext()) {
            Document dbObj = cursor.next();
            @SuppressWarnings("deprecation")
            JSONObject body = JSONObject.parseObject(dbObj.getString("message"));
            if(null==body)
                continue;
            if (null != body.get("isEncrypt") && true==body.getBoolean("isEncrypt")) {
                dbObj.put("isEncrypt", 1);
            } else {
                dbObj.put("isEncrypt", 0);
            }
            try {
                dbObj.put("content", body.getString("content"));
                dbObj.put("fromUserName", body.get("fromUserName"));
            } catch (Exception e) {
                dbObj.put("content", "--");
            }
            pageData.add(dbObj);

        }

        result.setData(pageData);
        result.setCount(total);
        return result;
    }

    @Override
    public void groupchat_logs_all_del(long startTime, long endTime,
                                       String msgId, String room_jid_id)throws Exception {

        MongoCollection<Document> dbCollection = getImRoomDB().getCollection(MUCMSG + room_jid_id);

        BasicDBObject q = new BasicDBObject();

        String[] msgIds = StringUtil.getStringList(msgId);
        for (String strMsgId : msgIds) {
            q.put("_id", new ObjectId(strMsgId));
            dbCollection.deleteMany(q);
        }

        if (0 != startTime)
            q.put("ts", new BasicDBObject("$gte", startTime));
        if (0 != endTime)
            q.put("ts", new BasicDBObject("$lte", endTime));

        dbCollection.deleteMany(q);
    }

    @Override
    public void groupchatMsgDel(String roomJid,
                                int type) {

        MongoCollection<Document> dbCollection = getImRoomDB().getCollection(MUCMSG + roomJid);
        BasicDBObject q = new BasicDBObject();
        try {
            if (0 == type) {
                // ?????????????????????????????????
                long onedayNextDay = DateUtil.getOnedayNextDay(DateUtil.currentTimeSeconds(), 30, 1);
                logger.info("?????????????????????" + onedayNextDay);
                q.put("timeSend", new BasicDBObject("$lte", onedayNextDay));
                dbCollection.deleteMany(q);
            } else if (1 == type) {
                final int num = 100000;
                int count = (int) dbCollection.count();
                if (count <= num)
                    throw new ServiceException("??????????????????" + num);
                // ?????????????????????????????????
                MongoCursor<Document> cursor = dbCollection.find().sort(new BasicDBObject("timeSend", -1)).skip(num).limit(count).iterator();
                while (cursor.hasNext()) {
                    dbCollection.deleteMany(cursor.next());
                }
                logger.info("??????" + num + "???????????????" + count);
            }
        } catch (Exception e) {
            throw e;
        }
    }


    @Override
    public  PageResult<Document> roomDetail(int page, int limit, String room_jid_id) {

        MongoCollection<Document> dbCollection = getImRoomDB().getCollection(MUCMSG + room_jid_id);
        Document q = new Document();
        q.put("contentType", 1);
        if (!StringUtil.isEmpty(room_jid_id))
            q.put("room_jid_id", room_jid_id);
        long total = dbCollection.count(q);
        logger.info("?????? ?????????" + total);

        List<Document> pageData = Lists.newArrayList();
        MongoCursor<Document> cursor = dbCollection.find(q).sort(new Document("_id", 1)).skip((page - 1) * limit).limit(limit).iterator();
        while (cursor.hasNext()) {
            Document dbObj =  cursor.next();
            try {
                JSONObject body = JSONObject.parseObject(dbObj.getString("message"));
                dbObj.put("content", body.get("content"));
                dbObj.put("fromUserName", body.get("fromUserName"));
            } catch (Exception e) {
                dbObj.put("content", "--");
                logger.error(e.getMessage());
            }
            pageData.add(dbObj);
        }
        PageResult<Document> result = new PageResult<Document>();
        result.setData(pageData);
        result.setCount(total);
        return result;
    }



}
