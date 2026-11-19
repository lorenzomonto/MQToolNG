/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package MQApi.Utilities;

import MQApi.Connection.MQConnection;
import MQApi.Logs.LogWriter;
import MQApi.Models.MQMessageIdModel;
import MQApi.PCF.MQPCF;
import MQApi.QueryModel.MQMessageListResult;
import MQApi.QueryModel.MQMessageListResult.MessageDetailModel;
import UI.Dialogs.MessageEditDialog;
import UI.Helpers.DateTimeHelper;
import UI.Helpers.TreeHelper;
import com.ibm.mq.MQException;
import com.ibm.mq.MQGetMessageOptions;
import com.ibm.mq.MQMessage;
import com.ibm.mq.MQPutMessageOptions;
import com.ibm.mq.MQQueue;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.CMQC;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.headers.CCSID;
import com.ibm.mq.headers.MQChainable;
import com.ibm.mq.headers.MQDLH;
import com.ibm.mq.headers.MQDataException;
import com.ibm.mq.headers.MQHeader;
import com.ibm.mq.headers.MQHeaderIterator;
import com.ibm.mq.headers.MQHeaderList;
import com.ibm.mq.headers.MQRFH2;
import com.ibm.mq.headers.internal.Header;
import com.ibm.mq.headers.pcf.PCFMessage;
import com.ibm.mq.headers.pcf.PCFParameter;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.*;
import javax.swing.JProgressBar;

/**
 *
 * @author jzhou
 */
public class MQUtility {
    
    private static int messageListDataLength = 50;
    /**
     * Bytes read of each message for the message list: enough for the headers and the preview, without
     * transferring large messages. The lengths shown stay those of the whole message.
     */
    static final int LIST_BUFFER = 16384;

    public static boolean PutMessage(MQQueueManager queueManager, String queueName, MQMessage message){    
        MQQueue queue = null;
        try {
            // On a remote queue manager the message goes there through the channels of the connected one
            queue = queueManager.accessQueue(queueName, CMQC.MQOO_OUTPUT, MQConnection.IsRemote(queueManager) ? MQConnection.RemoteTarget(queueManager) : "", null, null);
            queue.put(message);
            closeQueue(queue);
        } catch (Exception ex) {
            LogWriter.WriteToLog("MQUtility", "PutMessage", ex);
            closeQueue(queue);
            return false;
        }
        return true;
    }  
    
    public static int GetQueueDepth(MQQueueManager queueManager, String queueName){
        MQQueue queue = null;
        int result = 0;
        try {
            queue = queueManager.accessQueue(queueName, CMQC.MQOO_INQUIRE | CMQC.MQOO_BROWSE);
            result = queue.getCurrentDepth();
        } catch (MQException ex) {
            LogWriter.WriteToLog("MQUtility", "GetQueueDepth", ex);
        }
        closeQueue(queue);
        return result;
    }
    
    public static MQMessage GetMessage(MQQueueManager queueManager, String queueName, MQMessageIdModel messageIdModel, boolean matchPosition, boolean asEBCDIC) throws MQException{
        MQQueue queue = null;
        MQMessage message = new MQMessage();
        if(asEBCDIC){
            message.characterSet = 500;
            message.encoding = 785;
        }
        MQGetMessageOptions options = new MQGetMessageOptions();
        options.options = asEBCDIC ? CMQC.MQGMO_BROWSE_NEXT | CMQC.MQGMO_CONVERT : CMQC.MQGMO_BROWSE_NEXT;
        try {
            queue = queueManager.accessQueue(queueName, CMQC.MQOO_INQUIRE | CMQC.MQOO_BROWSE);
            if(matchPosition){
                setQueueCursor(queue, messageIdModel.position - 1);
            }
            getSelectedMessage(queue, message, options, messageIdModel, null);

        } catch (MQException ex) {
            LogWriter.WriteToLog("MQUtility", "GetMessage", ex);
            closeQueue(queue);
            throw ex;           
        }
        closeQueue(queue);
        //String aa = getDefaultCharSet();   
        return message;                
    }
    
    public static MQMessage GetMessageWithInterval(MQQueueManager queueManager, String queueName, int interval) throws MQException{
        MQQueue queue = null;
        MQMessage message = new MQMessage();
        MQGetMessageOptions options = new MQGetMessageOptions();
        options.options = CMQC.MQGMO_WAIT | CMQC.MQGMO_NO_SYNCPOINT;
        options.waitInterval  = interval;
        try {                 
            queue = queueManager.accessQueue(queueName, CMQC.MQOO_INQUIRE | CMQC.MQOO_BROWSE | CMQC.MQOO_OUTPUT | CMQC.MQOO_INPUT_SHARED);
            queue.get(message, options);

        } catch (MQException ex) {
            //LogWriter.WriteToLog("MQUtility", "GetMessage", ex);
            closeQueue(queue);
            throw ex;           
        }
        closeQueue(queue);
        return message;                
    }
    
    /**
     * Replaces the message read with oldId (its token, when known, removes exactly that message) with the
     * edited one, in one unit of work: if the put fails, the old message stays on the queue.
     */
    public static void UpdateMessage(MQQueueManager queueManager, String queueName, MQMessage message, MQMessageIdModel oldId) throws MQException{
        queueName = MQPCF.ResolveAliasBaseQueueName(queueManager, queueName);
        synchronized(UnitOfWork.Lock(queueManager)){
            MQQueue queue = null;
            try{
                queue = queueManager.accessQueue(queueName, CMQC.MQOO_INQUIRE | CMQC.MQOO_INPUT_SHARED | CMQC.MQOO_OUTPUT | CMQC.MQOO_SET_ALL_CONTEXT);
                removeUnderSyncpoint(queue, oldId);
                queue.put(message, syncpointPutOptions());
                queueManager.commit();
            }
            catch(MQException ex){
                LogWriter.WriteToLog("MQUtility", "UpdateMessage", ex);
                UnitOfWork.Backout(queueManager);
                throw ex;
            }
            finally{
                closeQueue(queue);
            }
        }
    }

    /**
     * Replaces the message with the edited one at the same position: the messages after it are taken
     * and put again after the edited one, all in one unit of work. If something fails, or the
     * program stops, the queue manager puts everything back as it was. The messages keep their
     * properties and context.
     */
    public static void UpdateMessageToSamePosition(MQQueueManager queueManager, String queueName, MQMessage message, MQMessageIdModel id) throws MQException, Exception{
        queueName = MQPCF.ResolveAliasBaseQueueName(queueManager, queueName);
        synchronized(UnitOfWork.Lock(queueManager)){
            MQQueue queue = null;
            try{
                queue = queueManager.accessQueue(queueName, CMQC.MQOO_INQUIRE | CMQC.MQOO_BROWSE | CMQC.MQOO_INPUT_SHARED | CMQC.MQOO_OUTPUT | CMQC.MQOO_SET_ALL_CONTEXT);
                List<MQMessageIdModel> after = messagesAfter(queue, id);
                removeUnderSyncpoint(queue, id);
                MQPutMessageOptions putOptions = syncpointPutOptions();
                queue.put(message, putOptions);
                for(MQMessageIdModel next : after){
                    MQMessage moved = new MQMessage();
                    MQGetMessageOptions getOptions = new MQGetMessageOptions();
                    getOptions.options = CMQC.MQGMO_SYNCPOINT;
                    try{
                        getSelectedMessage(queue, moved, getOptions, next, null);
                    }
                    catch(MQException ex){
                        if(ex.getReason() == MQConstants.MQRC_NO_MSG_AVAILABLE){
                            // Taken by an application in the meantime
                            continue;
                        }
                        throw ex;
                    }
                    queue.put(moved, putOptions);
                }
                queueManager.commit();
            }
            catch(MQException ex){
                LogWriter.WriteToLog("MQUtility", "UpdateMessageToSamePosition", ex);
                UnitOfWork.Backout(queueManager);
                if(ex.getReason() == MQConstants.MQRC_SYNCPOINT_LIMIT_REACHED){
                    throw new Exception("Too many messages after this one to keep its position in one unit of work "
                            + "(MAXUMSGS of the queue manager): the queue is unchanged. Update it without 'Keep position'.");
                }
                throw ex;
            }
            finally{
                closeQueue(queue);
            }
        }
    }

    // The messages after the one with the id, in queue order (only their ids are read)
    private static List<MQMessageIdModel> messagesAfter(MQQueue queue, MQMessageIdModel id) throws MQException{
        List<MQMessageIdModel> after = new ArrayList<>();
        MQMessage message = new MQMessage();
        MQGetMessageOptions options = new MQGetMessageOptions();
        options.options = CMQC.MQGMO_BROWSE_NEXT | CMQC.MQGMO_ACCEPT_TRUNCATED_MSG;
        try{
            getSelectedMessage(queue, message, options, id, 0);
        }
        catch(MQException ex){
            if(ex.getReason() != MQConstants.MQRC_TRUNCATED_MSG_ACCEPTED){
                throw ex;
            }
        }
        options.matchOptions = CMQC.MQMO_NONE;
        while(true){
            resetMessage(message);
            try{
                queue.get(message, options, 0);
            }
            catch(MQException ex){
                if(ex.getReason() == MQConstants.MQRC_NO_MSG_AVAILABLE){
                    break;
                }
                if(ex.getReason() != MQConstants.MQRC_TRUNCATED_MSG_ACCEPTED){
                    throw ex;
                }
            }
            after.add(idOf(message, options));
        }
        return after;
    }

    private static MQMessageIdModel idOf(MQMessage message, MQGetMessageOptions options){
        MQMessageIdModel id = new MQMessageIdModel();
        id.MessageId = message.messageId.clone();
        id.CorrelationdId = message.correlationId.clone();
        id.MessageToken = options.msgToken != null ? options.msgToken.clone() : null;
        return id;
    }

    // Gets the message destructively under syncpoint, without reading its data; false when it is no longer on the queue
    private static boolean removeUnderSyncpoint(MQQueue queue, MQMessageIdModel id) throws MQException{
        MQMessage message = new MQMessage();
        MQGetMessageOptions options = new MQGetMessageOptions();
        options.options = CMQC.MQGMO_SYNCPOINT | CMQC.MQGMO_ACCEPT_TRUNCATED_MSG;
        try{
            getSelectedMessage(queue, message, options, id, 0);
        }
        catch(MQException ex){
            if(ex.getReason() == MQConstants.MQRC_TRUNCATED_MSG_ACCEPTED){
                return true;
            }
            if(ex.getReason() == MQConstants.MQRC_NO_MSG_AVAILABLE){
                return false;
            }
            throw ex;
        }
        return true;
    }

    private static MQPutMessageOptions syncpointPutOptions(){
        MQPutMessageOptions options = new MQPutMessageOptions();
        options.options = CMQC.MQPMO_SET_ALL_CONTEXT | CMQC.MQPMO_SYNCPOINT;
        return options;
    }

    private static void progress(JProgressBar progressBar, int from, int to, int done, int total){
        if(progressBar != null && total > 0){
            progressBar.setValue(from + (to - from) * Math.min(done, total) / total);
        }
    }

    public static MQMessageListResult GetMessageList(MQQueueManager queueManager, String queueName, int numOfMessageRequired,boolean isAlias){
        return GetMessageList(queueManager, queueName, numOfMessageRequired, isAlias, null, 0);
    }
          
    public static MQMessageListResult GetMessageList(MQQueueManager queueManager, String queueName, int numOfMessageRequired,boolean isAlias, MQMessageIdModel fromId, int fromPosition){
        MQMessageListResult result = new MQMessageListResult();
        MQQueue queue = null;
        try {
            if(isAlias){
                queueName = MQPCF.ResolveAliasBaseQueueName(queueManager, queueName);
            }
            queue = openToBrowse(queueManager, queueName);
            int queueDepth = queue.getCurrentDepth();
            int index = numOfMessageRequired > 0 &&  numOfMessageRequired < (queueDepth - fromPosition + 1) ? numOfMessageRequired : (queueDepth - fromPosition + 1);
            int position = fromPosition;
            MQMessage message = new MQMessage();
            MQGetMessageOptions options = new MQGetMessageOptions();
            options.options = CMQC.MQGMO_BROWSE_NEXT | CMQC.MQGMO_ACCEPT_TRUNCATED_MSG;
            options.matchOptions = MQConstants.MQMO_MATCH_MSG_ID  | MQConstants.MQMO_MATCH_CORREL_ID;
            if(fromId != null){
                try{
                    // Only the browse cursor is needed: no data
                    getSelectedMessage(queue, message, options, fromId, 0);
                }
                catch(MQException ex){
                    if(ex.getReason() != MQConstants.MQRC_TRUNCATED_MSG_ACCEPTED && !setQueueCursor(queue, fromPosition)){
                        closeQueue(queue);
                        return result;
                    }                                        
                }
            }
            while(index > 0){ 
                resetMessage(message);
                boolean truncated = false;
                try{
                    queue.get(message, options, LIST_BUFFER);
                }
                catch(MQException ex){
                    if(ex.getReason() == MQConstants.MQRC_NO_MSG_AVAILABLE){
                        break;
                    }
                    else if(ex.getReason() == MQConstants.MQRC_TRUNCATED_MSG_ACCEPTED){
                        // A large message: its first LIST_BUFFER bytes are enough for the list
                        truncated = true;
                    }
                    else{
                        LogWriter.WriteToLog("MQUtility", "GetMessageList", ex);
                        throw ex;
                    }
                }
                index--;
                MessageDetailModel model = turnToMessageModel(result, message, position, truncated ? LIST_BUFFER : 0);
                model.MessageToken = options.msgToken != null ? options.msgToken.clone() : null;
                result.DataModels.add(model);
                position ++;
            }            
        } catch (MQException ex) {
            LogWriter.WriteToLog("MQUtility", "GetMessageList", ex);
            result.QuerySuccess = false;
            result.ErrorMessage = getMQReturnMessage(ex.getCompCode(), ex.getReason());
            closeQueue(queue);
            return result;
        }
        closeQueue(queue);
        result.QuerySuccess = true;
        return result;
    }

    /**
     * Opens the queue to browse it with read ahead: the queue manager sends the next (non persistent)
     * messages before they are asked for, instead of one network round trip per message. A queue
     * manager that does not accept it (older than MQ 7) gets the usual open.
     */
    private static MQQueue openToBrowse(MQQueueManager queueManager, String queueName) throws MQException{
        try{
            return queueManager.accessQueue(queueName, CMQC.MQOO_INQUIRE | CMQC.MQOO_BROWSE | CMQC.MQOO_READ_AHEAD);
        }
        catch(MQException ex){
            if(ex.getReason() != MQConstants.MQRC_OPTIONS_ERROR && ex.getReason() != MQConstants.MQRC_OPTION_NOT_VALID_FOR_TYPE){
                throw ex;
            }
            return queueManager.accessQueue(queueName, CMQC.MQOO_INQUIRE | CMQC.MQOO_BROWSE);
        }
    }

    public static void ComsumeMessagesThread(MQQueueManager queueManager, String queueName, boolean forceOpenGet, boolean isAlias, Observer observer){
        MQQueue queue = null;
        try {
            int openQueueOptions;
            
            if(isAlias){
                queueName = MQPCF.ResolveAliasBaseQueueName(queueManager, queueName);
            }
            openQueueOptions = forceOpenGet == true? CMQC.MQOO_INQUIRE | CMQC.MQOO_BROWSE | CMQC.MQOO_INPUT_SHARED | CMQC.MQOO_SET : CMQC.MQOO_INQUIRE | CMQC.MQOO_BROWSE | CMQC.MQOO_INPUT_SHARED;
            queue = queueManager.accessQueue(queueName, openQueueOptions);
            boolean isGetOpen = queue.getInhibitGet() == MQConstants.MQQA_GET_ALLOWED;
            if(forceOpenGet == true && isGetOpen == false){
                queue.setInhibitGet(MQConstants.MQQA_GET_ALLOWED);
            }
            MQGetMessageOptions options = new MQGetMessageOptions();
            MQMessage message = new MQMessage();
            options.matchOptions = MQConstants.MQMO_NONE;
            options.options = CMQC.MQGMO_ACCEPT_TRUNCATED_MSG;
            while(true){               
                try{
                    queue.get(message, options, 1);
                }
                catch(MQException ex){
                    if(ex.getReason() == MQConstants.MQRC_TRUNCATED_MSG_ACCEPTED){  
                        observer.Action();
                        continue;
                    }  
                    else if(ex.getReason() == MQConstants.MQRC_NO_MSG_AVAILABLE){
                        break;
                    }
                    else{ 
                        LogWriter.WriteToLog("MQUtility", "ComsumeAllMessages",ex);
                        throw ex;
                    }
                }               
            }
            if(forceOpenGet == true && isGetOpen == false){
                queue.setInhibitGet(MQConstants.MQQA_GET_INHIBITED);
            }
            closeQueue(queue);      
        } catch (MQException ex) {
            LogWriter.WriteToLog("MQUtility", "ComsumeAllMessages",ex);     
            closeQueue(queue);
        }  
    }
    public static void ComsumeAllMessagesMultiThread(MQQueueManager queueManager, String queueName, JProgressBar progressBar, boolean forceOpenGet, boolean isAlias) throws MQException{
        MQQueue queue = null;
        try{
            int openQueueOptions;
            if(isAlias){
                queueName = MQPCF.ResolveAliasBaseQueueName(queueManager, queueName);
            }
            openQueueOptions = forceOpenGet == true? CMQC.MQOO_INQUIRE | CMQC.MQOO_BROWSE | CMQC.MQOO_INPUT_SHARED | CMQC.MQOO_SET : CMQC.MQOO_INQUIRE | CMQC.MQOO_BROWSE | CMQC.MQOO_INPUT_SHARED;
            queue = queueManager.accessQueue(queueName, openQueueOptions);
            final int queueDepth = queue.getCurrentDepth();
            Processed processed = new Processed();
            processed.MessageDeleted = 0;
            ThreadFinish thread1Finish = new ThreadFinish();
            thread1Finish.IsFinish = false;
            ThreadFinish thread2Finish = new ThreadFinish();
            thread2Finish.IsFinish = false;
            ThreadFinish thread3Finish = new ThreadFinish();
            thread3Finish.IsFinish = false;
            ThreadFinish thread4Finish = new ThreadFinish();
            thread4Finish.IsFinish = false;
            
            MQQueueManager queueManager1 = MQConnection.GetMQQueueManager(TreeHelper.GetCurrentConnectionDetail(null));
            MQQueueManager queueManager2 = MQConnection.GetMQQueueManager(TreeHelper.GetCurrentConnectionDetail(null));
            MQQueueManager queueManager3 = MQConnection.GetMQQueueManager(TreeHelper.GetCurrentConnectionDetail(null));
            MQQueueManager queueManager4 = MQConnection.GetMQQueueManager(TreeHelper.GetCurrentConnectionDetail(null));
            
            ObserverTask observerTask1 = new ObserverTask(progressBar,queueDepth, processed);
            ConsumeTask task1 = new ConsumeTask(queueManager1, queueName, forceOpenGet, isAlias, observerTask1, thread1Finish);
            Thread thread1 = new Thread(task1);
            
            ObserverTask observerTask2 = new ObserverTask(progressBar,queueDepth, processed);
            ConsumeTask task2 = new ConsumeTask(queueManager2, queueName, forceOpenGet, isAlias, observerTask2, thread2Finish);
            Thread thread2 = new Thread(task2);
  
            ObserverTask observerTask3 = new ObserverTask(progressBar,queueDepth, processed);
            ConsumeTask task3 = new ConsumeTask(queueManager3, queueName, forceOpenGet, isAlias, observerTask3, thread3Finish);
            Thread thread3 = new Thread(task3);
            
            ObserverTask observerTask4 = new ObserverTask(progressBar,queueDepth, processed);
            ConsumeTask task4 = new ConsumeTask(queueManager4, queueName, forceOpenGet, isAlias, observerTask4, thread4Finish);
            Thread thread4 = new Thread(task4);
            
            thread1.start();
            thread2.start();
            thread3.start();
            thread4.start();
            
            while(true){
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                    
                }
                if(thread1Finish.IsFinish && thread2Finish.IsFinish && thread3Finish.IsFinish && thread4Finish.IsFinish){
                    progressBar.setValue(100);
                    LogWriter.WriteActivityToLog("MQUtility (ComsumeAllMessagesMultiThread) : Removed " + processed.MessageDeleted + " msgs from " + queueName + "(" + MQApi.Connection.MQConnection.QueueManagerName(queueManager) + ")");
                    break;
                }
            }
        }
        catch (MQException ex){
            ComsumeAllMessages(queueManager, queueName, progressBar, forceOpenGet, isAlias);
        }
    }
    public static void ComsumeAllMessages(MQQueueManager queueManager, String queueName, JProgressBar progressBar, boolean forceOpenGet, boolean isAlias) throws MQException{
        MQQueue queue = null;
        int removedMsgCount = 0;
        try {
            int openQueueOptions;
            
            if(isAlias){
                queueName = MQPCF.ResolveAliasBaseQueueName(queueManager, queueName);
            }
            openQueueOptions = forceOpenGet == true? CMQC.MQOO_INQUIRE | CMQC.MQOO_BROWSE | CMQC.MQOO_INPUT_SHARED | CMQC.MQOO_SET : CMQC.MQOO_INQUIRE | CMQC.MQOO_BROWSE | CMQC.MQOO_INPUT_SHARED;
            queue = queueManager.accessQueue(queueName, openQueueOptions);
            boolean isGetOpen = queue.getInhibitGet() == MQConstants.MQQA_GET_ALLOWED;
            if(forceOpenGet == true && isGetOpen == false){
                queue.setInhibitGet(MQConstants.MQQA_GET_ALLOWED);
            }
            int queueDepth = queue.getCurrentDepth();
            int index = queueDepth;
            MQGetMessageOptions options = new MQGetMessageOptions();
            MQMessage message = new MQMessage();
            options.matchOptions = MQConstants.MQMO_NONE;
            options.options = CMQC.MQGMO_ACCEPT_TRUNCATED_MSG;
            while(index > 0){               
                try{
                    index--;
                    if(progressBar != null){
                        int value = ((queueDepth - index)*100)/queueDepth;
                        progressBar.setValue(value);
                    }
                    queue.get(message, options, 1);
                }
                catch(MQException ex){
                    if(ex.getReason() == MQConstants.MQRC_TRUNCATED_MSG_ACCEPTED){       
                        removedMsgCount++;                 
                        continue;
                    }  
                    else if(ex.getReason() == MQConstants.MQRC_NO_MSG_AVAILABLE){
                        break;
                    }
                    else{
                        LogWriter.WriteToLog("MQUtility", "ComsumeAllMessages", ex);
                        throw ex;
                    }
                }               
            }
            if(forceOpenGet == true && isGetOpen == false){
                queue.setInhibitGet(MQConstants.MQQA_GET_INHIBITED);
            }
            closeQueue(queue);
            LogWriter.WriteActivityToLog("MQUtility (ComsumeAllMessages) : Removed " + removedMsgCount + " msgs from " + queueName + "(" + MQApi.Connection.MQConnection.QueueManagerName(queueManager) + ")");
            if(progressBar != null){
                progressBar.setValue(100);
            }
        } catch (MQException ex) {
            LogWriter.WriteToLog("MQUtility", "ComsumeAllMessages",ex);
            LogWriter.WriteActivityToLog("MQUtility (ComsumeAllMessages) : Removed " + removedMsgCount + " msgs from " + queueName + "(" + MQApi.Connection.MQConnection.QueueManagerName(queueManager) + ")");
            closeQueue(queue);
            throw ex;
        }
    }
    public static void ComsumeAllMessagesWithFilter(MQQueueManager queueManager, String queueName, JProgressBar progressBar, boolean forceOpenGet, boolean isAlias, String stringfilter) throws MQException{
        MQQueue queue = null;
        int removedMsgCount = 0;
        try {
            int openQueueOptions;
            
            if(isAlias){
                queueName = MQPCF.ResolveAliasBaseQueueName(queueManager, queueName);
            }
            openQueueOptions = forceOpenGet == true? CMQC.MQOO_INQUIRE | CMQC.MQOO_BROWSE | CMQC.MQOO_INPUT_SHARED | CMQC.MQOO_SET : CMQC.MQOO_INQUIRE | CMQC.MQOO_BROWSE | CMQC.MQOO_INPUT_SHARED;
            queue = queueManager.accessQueue(queueName, openQueueOptions);
            int queueDepth = queue.getCurrentDepth();
            int index = queueDepth;
            MQGetMessageOptions options = new MQGetMessageOptions();
            MQMessage message = new MQMessage();
            options.matchOptions = MQConstants.MQMO_NONE;
            options.options = CMQC.MQGMO_BROWSE_NEXT;
            ArrayList<MQMessageIdModel> ids = new ArrayList<MQMessageIdModel>();
            if(progressBar != null){
                progressBar.setValue(0);
            }
            while(index > 0){               
                try{
                    index--;
                    options.options = CMQC.MQGMO_BROWSE_NEXT;
                    queue.get(message, options);
                    String content = message.readStringOfByteLength(message.getDataLength());
                    if(content.contains(stringfilter)){
                        resetMessage(message);
                        options.options = CMQC.MQGMO_MSG_UNDER_CURSOR;
                        queue.get(message, options);
                        removedMsgCount++;
                    }
                    resetMessage(message);
                }
                 catch (IOException ex) {
                    Logger.getLogger(MQUtility.class.getName()).log(Level.SEVERE, null, ex);
                }               
            }
            closeQueue(queue);
            ComsumeSelectedMessages(queueManager, queueName, ids, progressBar, forceOpenGet, isAlias);
        } catch (MQException ex) {
            LogWriter.WriteToLog("MQUtility", "ComsumeAllMessages",ex);
            closeQueue(queue);
            throw ex;
        }
        if(progressBar != null){
            progressBar.setValue(100);
        }
        LogWriter.WriteToLog("MQUtility (ComsumeAllMessagesWithFilter) : Removed " + removedMsgCount + " msgs from " + queueName + "(" + MQApi.Connection.MQConnection.QueueManagerName(queueManager) + ")");
    }    
    public static void ComsumeSelectedMessages(MQQueueManager queueManager, String queueName, MQMessageIdModel id) throws MQException{
        ArrayList<MQMessageIdModel> ids = new ArrayList<MQMessageIdModel>();
        ids.add(id);
        queueName = MQPCF.ResolveAliasBaseQueueName(queueManager, queueName);
        ComsumeSelectedMessages(queueManager, queueName, ids, null, false, false);
    }
    public static void ComsumeSelectedMessages(MQQueueManager queueManager, String queueName, ArrayList<MQMessageIdModel>ids, JProgressBar progressBar, boolean forceOpenGet, boolean isAlias) throws MQException {
        MQQueue queue = null;
        int removedMsgCount = 0;
        try {
            int openQueueOptions;
            if(isAlias){
                queueName = MQPCF.ResolveAliasBaseQueueName(queueManager, queueName);
            }
            openQueueOptions = forceOpenGet == true? CMQC.MQOO_INQUIRE | CMQC.MQOO_BROWSE | CMQC.MQOO_INPUT_SHARED | CMQC.MQOO_SET : CMQC.MQOO_INQUIRE | CMQC.MQOO_BROWSE | CMQC.MQOO_INPUT_SHARED;
            queue = queueManager.accessQueue(queueName, openQueueOptions);
            boolean isGetOpen = queue.getInhibitGet() == MQConstants.MQQA_GET_ALLOWED;
            if(forceOpenGet == true && isGetOpen == false){
                queue.setInhibitGet(MQConstants.MQQA_GET_ALLOWED);
            }
            int i = 1;
            MQMessage message = new MQMessage();
            MQGetMessageOptions options = new MQGetMessageOptions();
            for(MQMessageIdModel id : ids){
                options.options = CMQC.MQGMO_ACCEPT_TRUNCATED_MSG;

                if(progressBar != null){
                    int value = i*100/ids.size();
                    progressBar.setValue(value);
                }
                i++;
                try{
                    getSelectedMessage(queue, message, options, id, 1);
                }
                catch(MQException ex){
                    if(ex.getReason() == MQConstants.MQRC_TRUNCATED_MSG_ACCEPTED || ex.getReason() == MQConstants.MQRC_NO_MSG_AVAILABLE){
                        removedMsgCount++;
                        continue;
                    }   
                    else{
                        throw ex;
                    }
                }
            }
            if(forceOpenGet == true && isGetOpen == false){
                queue.setInhibitGet(MQConstants.MQQA_GET_INHIBITED);
            }
            closeQueue(queue);
            if(progressBar != null){
                progressBar.setValue(100);
                LogWriter.WriteActivityToLog("MQUtility (ComsumeSelectedMessages) : Removed " + removedMsgCount + " msgs from " + queueName + "(" + MQApi.Connection.MQConnection.QueueManagerName(queueManager) + ")");
            }
        } catch (MQException ex) {
            LogWriter.WriteToLog("MQUtility", "ComsumeSelectedMessages",ex);
            LogWriter.WriteActivityToLog("MQUtility (ComsumeSelectedMessages) : Removed " + removedMsgCount + " msgs from " + queueName + "(" + MQApi.Connection.MQConnection.QueueManagerName(queueManager) + ")");
            closeQueue(queue);
            throw ex;
        }
    }
    
    public static String getMQReturnMessage(int compCode, int reason){
            String msg = MQConstants.lookupCompCode(compCode) + " : (" + MQConstants.lookupReasonCode(reason) +" )";
            msg = msg.replace("_", " ");
            msg =  msg.toLowerCase();
            msg = msg.replace("mqcc", "Command");
            msg = msg.replace("mqrccf", "");
            msg = msg.replace("mqrc", "");
            return msg;
    }

    /**
     * Saves messages of the queue to a message file: all of them, the ones with the ids, or the ones
     * whose text contains filterString. With removeAfter, the saved messages (and only those) are removed
     * once the whole file is on the disk, in units of work: whatever fails, each message is in the file,
     * on the queue, or both. Returns the number of messages saved.
     */
    public static int BackupMessageToFile(MQQueueManager queueManager, String queueName, String filePath, JProgressBar progressBar, ArrayList<MQMessageIdModel>ids, boolean isCompress, boolean isAlias, boolean removeDLH, boolean removeAfter, String filterString) throws Exception{
        return BackupMessageToFile(queueManager, queueName, filePath, progressBar, ids, isCompress, isAlias, removeDLH, removeAfter, filterString, false);
    }

    /** As above; dmpmqmsg true writes a file of IBM dmpmqmsg, with the message properties in an MQRFH2 header. */
    public static int BackupMessageToFile(MQQueueManager queueManager, String queueName, String filePath, JProgressBar progressBar, ArrayList<MQMessageIdModel>ids, boolean isCompress, boolean isAlias, boolean removeDLH, boolean removeAfter, String filterString, boolean dmpmqmsg) throws Exception{
        if(isAlias){
            queueName = MQPCF.ResolveAliasBaseQueueName(queueManager, queueName);
        }
        MQQueue queue = null;
        List<MQMessageIdModel> saved = new ArrayList<>();
        int savedEnd = removeAfter ? 50 : 100;
        try{
            queue = queueManager.accessQueue(queueName, CMQC.MQOO_INQUIRE | CMQC.MQOO_BROWSE | (removeAfter ? CMQC.MQOO_INPUT_SHARED : 0));
            int queueDepth = queue.getCurrentDepth();
            if(queueDepth == 0){
                throw new Exception("Queue depth is 0");
            }
            String queueManagerName = MQApi.Connection.MQConnection.QueueManagerName(queueManager);
            // dmpmqmsg keeps the message properties in the data, as an MQRFH2 header
            int properties = dmpmqmsg ? CMQC.MQGMO_PROPERTIES_FORCE_MQRFH2 : 0;
            try(MessageFile.Output writer = dmpmqmsg
                    ? new DmpmqmsgFile.Writer(filePath, queueName, queueManagerName, "MQToolNG")
                    : new MessageFile.Writer(filePath, isCompress, queueName, queueManagerName)){
                if(ids == null){
                    MQGetMessageOptions options = new MQGetMessageOptions();
                    options.options = CMQC.MQGMO_BROWSE_NEXT | properties;
                    options.matchOptions = CMQC.MQMO_NONE;
                    int read = 0;
                    while(true){
                        MQMessage message = new MQMessage();
                        try{
                            queue.get(message, options);
                        }
                        catch(MQException ex){
                            if(ex.getReason() == MQConstants.MQRC_NO_MSG_AVAILABLE){
                                break;
                            }
                            throw ex;
                        }
                        if(filterString == null || filterString.isEmpty() || GetMessageStringContent(message, null).contains(filterString)){
                            writer.Write(removeDLH ? RemoveMQDLH(message) : message);
                            saved.add(idOf(message, options));
                        }
                        progress(progressBar, 0, savedEnd, ++read, queueDepth);
                    }
                }
                else{
                    for(MQMessageIdModel id : ids){
                        MQMessage message = new MQMessage();
                        MQGetMessageOptions options = new MQGetMessageOptions();
                        options.options = CMQC.MQGMO_BROWSE_NEXT | properties;
                        getSelectedMessage(queue, message, options, id, null);
                        writer.Write(removeDLH ? RemoveMQDLH(message) : message);
                        saved.add(id);
                        progress(progressBar, 0, savedEnd, saved.size(), ids.size());
                    }
                }
                writer.Finish();
            }
        }
        catch(IOException ex){
            LogWriter.WriteToLog("MQUtility", "BackupMessageToFile",ex);
            closeQueue(queue);
            removeFile(filePath);
            throw new Exception("File access error: " + ex.getMessage());
        }
        catch(MQException ex){
            LogWriter.WriteToLog("MQUtility", "BackupMessageToFile",ex);
            closeQueue(queue);
            removeFile(filePath);
            throw new Exception(getMQReturnMessage(ex.getCompCode(), ex.getReason()));
        }
        catch(Exception ex){
            closeQueue(queue);
            throw ex;
        }
        try{
            if(removeAfter){
                removeSaved(queueManager, queue, saved, progressBar);
            }
        }
        finally{
            closeQueue(queue);
        }
        if(progressBar != null){
            progressBar.setValue(100);
        }
        return saved.size();
    }

    // Removes the saved messages in units of work; the file already contains all of them
    private static void removeSaved(MQQueueManager queueManager, MQQueue queue, List<MQMessageIdModel> saved, JProgressBar progressBar) throws Exception{
        int removed = 0;
        synchronized(UnitOfWork.Lock(queueManager)){
            int pending = 0;
            int batch = UnitOfWork.BATCH;
            try{
                for(MQMessageIdModel id : saved){
                    boolean taken;
                    try{
                        taken = removeUnderSyncpoint(queue, id);
                    }
                    catch(MQException ex){
                        if(ex.getReason() != MQConstants.MQRC_SYNCPOINT_LIMIT_REACHED || pending == 0){
                            throw ex;
                        }
                        // MAXUMSGS of the queue manager is lower than BATCH: units of work of this size
                        queueManager.commit();
                        removed += pending;
                        batch = pending;
                        pending = 0;
                        taken = removeUnderSyncpoint(queue, id);
                    }
                    if(taken){
                        pending++;
                    }
                    if(pending == batch){
                        queueManager.commit();
                        removed += pending;
                        pending = 0;
                    }
                    progress(progressBar, 50, 100, removed + pending, saved.size());
                }
                queueManager.commit();
                removed += pending;
            }
            catch(MQException ex){
                LogWriter.WriteToLog("MQUtility", "BackupMessageToFile (remove)", ex);
                UnitOfWork.Backout(queueManager);
                throw new Exception("All " + saved.size() + " messages are in the file, but only " + removed
                        + " were removed from the queue, the others are still on it: " + getMQReturnMessage(ex.getCompCode(), ex.getReason()));
            }
        }
        LogWriter.WriteActivityToLog("MQUtility (BackupMessageToFile) : Removed " + removed + " msgs after saving them");
    }
    
    public static void SaveMessageContentToFile(MQQueueManager queueManager, String queueName, String filePath, JProgressBar progressBar, ArrayList<MQMessageIdModel>ids) throws Exception{
            // The text of each message, decoded from its own character set, always written as UTF-8
            BufferedWriter out = new BufferedWriter(new FileWriter(new File(filePath), StandardCharsets.UTF_8));
            MQQueue queue = null;
            try{
                queue = queueManager.accessQueue(queueName, CMQC.MQOO_INQUIRE | CMQC.MQOO_BROWSE);
                int queueDepth = queue.getCurrentDepth();            
                if(queueDepth > 0){
                    int index = queueDepth;
                    if(ids == null){
                        int pos = 1;
                        while(index > 0){
                            MQMessage message = new MQMessage();
                            MQGetMessageOptions options = new MQGetMessageOptions();
                            options.options = CMQC.MQGMO_BROWSE_NEXT;
                            try{
                                queue.get(message, options);
                            }
                            catch(MQException ex){
                                if(ex.getReason() == MQConstants.MQRC_NO_MSG_AVAILABLE){
                                    if(progressBar != null){
                                        progressBar.setValue(100);
                                    }
                                    break;
                                }
                                else{
                                    throw ex;
                                }
                            }
                            out.write("Message : " + pos);
                            out.write("\r\n"); 
                            out.write("\r\n");
                            String content = GetMessageStringContent(message, null);
//                            if(content.toLowerCase().contains("<?xml")){
//                                content = XMLStringFormat(content);
//                            }
                            out.write(content);
                            out.write("\r\n"); 
                            out.write("\r\n"); 
                            index--;
                            pos++;
                            if(progressBar != null){
                                int value = ((queueDepth - index)*100)/queueDepth;
                                progressBar.setValue(value);
                            }
                        }
                    }
                    else{
                        int i = 1;
                        for(MQMessageIdModel id : ids){
                            MQMessage message = new MQMessage();
                            MQGetMessageOptions options = new MQGetMessageOptions();
                            options.options = CMQC.MQGMO_BROWSE_NEXT;
                            getSelectedMessage(queue, message, options, id, null);
                            out.write("Message position : " + i);
                            out.write("\r\n"); 
                            out.write("\r\n");
                            out.write(GetMessageStringContent(message, null));
                            out.write("\r\n"); 
                            out.write("\r\n");
                            if(progressBar != null){
                                int value = (i*100)/ids.size();
                                progressBar.setValue(value);
                            }  
                            i++;
                        }
                    }
                    closeQueue(queue);
                    out.flush();
                    out.close();
                }
                else {
                    closeQueue(queue);
                    out.close();
                    throw new Exception("Queue depth is 0");
                }
            }catch(IOException ex){
                LogWriter.WriteToLog("MQUtility", "SaveMessageContentToFile",ex);
                removeFile(filePath);
                closeQueue(queue);
                out.close();
                throw new Exception("File access error");
            }
            catch(MQException ex){
                LogWriter.WriteToLog("MQUtility", "SaveMessageContentToFile",ex);
                removeFile(filePath);
                closeQueue(queue);
                out.close();
                throw new Exception(getMQReturnMessage(ex.getCompCode(), ex.getReason()));                
            }
    
        
    }
 
    /**
     * Classes a backup file may contain: the values of the MQMessage fields (numbers, byte arrays,
     * dates with their time zone) and strings. A backup file comes from outside: any other class is
     * rejected, so a crafted file cannot run code while it is read (Java deserialization attack).
     */
    public static final ObjectInputFilter BACKUP_FILE_FILTER = ObjectInputFilter.Config.createFilter(
            "java.lang.String;java.lang.Number;java.lang.Integer;java.lang.Long;java.lang.Boolean;"
            + "java.util.Calendar;java.util.GregorianCalendar;java.util.TimeZone;java.util.SimpleTimeZone;"
            + "sun.util.calendar.ZoneInfo;[B;[I;[Z;"
            + "maxdepth=20;maxarray=10000000;!*");

    /** Messages put by a restore, and whether the file was read to its end. */
    public static class RestoreResult {
        public int Restored;
        public boolean Complete;
    }

    /**
     * Puts the messages of a message file (of any version) on the queue with their context and
     * properties, in units of work: after an error no message of the unit that failed is left on the queue.
     */
    public static RestoreResult RestoreMessageFromFile(MQQueueManager queueManager, String queueName, String filePath, JProgressBar progressBar, boolean isAlias, boolean checkQueueDepth) throws Exception{
        if(isAlias){
            queueName = MQPCF.ResolveAliasBaseQueueName(queueManager, queueName);
        }
        RestoreResult result = new RestoreResult();
        MQQueue queue = null;
        try(MessageFile.Input reader = MessageFile.OpenAny(filePath)){
            int total = MessageFile.CountAny(filePath);
            queue = queueManager.accessQueue(queueName, CMQC.MQOO_INQUIRE | CMQC.MQOO_OUTPUT | CMQC.MQOO_SET_ALL_CONTEXT);
            if(checkQueueDepth){
                int available = Integer.MAX_VALUE;
                try{
                    available = queue.getMaximumDepth() - queue.getCurrentDepth();
                }
                catch(MQException ex){
                    // Not a local queue: the depth is not known
                }
                if(total > available){
                    throw new Exception("Not enough available queue depth");
                }
            }
            MQPutMessageOptions options = syncpointPutOptions();
            synchronized(UnitOfWork.Lock(queueManager)){
                int pending = 0;
                int batch = UnitOfWork.BATCH;
                try{
                    MQMessage message;
                    while((message = reader.Next()) != null){
                        if(message.format != null && message.format.contains("MQXMIT")){
                            Field versionField = message.getClass().getSuperclass().getDeclaredField("version");
                            versionField.setAccessible(true);
                            versionField.set(message, 1);
                        }
                        try{
                            queue.put(message, options);
                        }
                        catch(MQException ex){
                            if(ex.getReason() != MQConstants.MQRC_SYNCPOINT_LIMIT_REACHED || pending == 0){
                                throw ex;
                            }
                            // MAXUMSGS of the queue manager is lower than BATCH: units of work of this size
                            queueManager.commit();
                            result.Restored += pending;
                            batch = pending;
                            pending = 0;
                            queue.put(message, options);
                        }
                        pending++;
                        if(pending == batch){
                            queueManager.commit();
                            result.Restored += pending;
                            pending = 0;
                        }
                        progress(progressBar, 0, 100, result.Restored + pending, total);
                    }
                    queueManager.commit();
                    result.Restored += pending;
                }
                catch(MQException | IOException | ReflectiveOperationException ex){
                    LogWriter.WriteToLog("MQUtility", "RestoreMessageFromFile", ex);
                    UnitOfWork.Backout(queueManager);
                    String reason = ex instanceof MQException ? getMQReturnMessage(((MQException) ex).getCompCode(), ((MQException) ex).getReason()) : ex.getMessage();
                    throw new Exception(result.Restored == 0 ? reason
                            : result.Restored + " messages were restored, the next ones were not: " + reason);
                }
            }
            result.Complete = reader.Complete();
        }
        catch(IOException ex){
            LogWriter.WriteToLog("MQUtility", "RestoreMessageFromFile", ex);
            throw new Exception(ex instanceof java.io.FileNotFoundException ? "File not found: " + filePath : ex.getMessage());
        }
        catch(MQException ex){
            LogWriter.WriteToLog("MQUtility", "RestoreMessageFromFile", ex);
            throw new Exception(getMQReturnMessage(ex.getCompCode(), ex.getReason()));
        }
        finally{
            closeQueue(queue);
        }
        if(progressBar != null){
            progressBar.setValue(100);
        }
        return result;
    }
  
    public static String BytesToHex(byte[] bytes) {
        char[] hexArray = "0123456789ABCDEF".toCharArray();
        char[] hexChars = new char[bytes.length * 2];
        for ( int j = 0; j < bytes.length; j++ ) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }
    
    public static MQDLH getDLH(MQHeaderList list){
         try {
            if(list.size() > 0 && list.indexOf("MQDLH") >= 0){
                MQDLH dlh = (MQDLH) list.get(list.indexOf("MQDLH"));
                return dlh;
            }
        } catch(Exception ex){

        }
        return null;       
    }
    
    public static MQRFH2 getMQHRF2 (MQHeaderList list){
         try {
            if(list.size() > 0 && list.indexOf("MQRFH2") >= 0){
                MQRFH2 mqrfh2 = (MQRFH2) list.get(list.indexOf("MQRFH2"));
                return mqrfh2;
            }
        } catch(Exception ex){

        }
        return null;          
    }
    
    /**
     * The message without its dead-letter header: the bytes after the DLH are kept as they are, with
     * the format, encoding and CCSID the DLH gives for them (they may differ from the MQMD ones, which
     * describe the DLH). A message without a DLH is returned unchanged.
     */
    public static MQMessage RemoveMQDLH(MQMessage message){
        try {
            message.seek(0);
            List<MQHeader> headers = ReadHeaders(message);
            MQDLH dlh = headers.isEmpty() ? null : FindHeader(headers.subList(0, 1), MQDLH.class);
            if(dlh == null){
                message.seek(0);
                return message;
            }
            message.seek(dlh.size());
            byte[] rest = new byte[message.getDataLength()];
            message.readFully(rest);
            MQMessage newMessage = new MQMessage();
            copyMessageMQMD(newMessage, message);
            MessageFile.CopyProperties(message, newMessage);
            // The DLH describes the data after it
            newMessage.format = dlh.getFormat();
            newMessage.encoding = dlh.getEncoding();
            newMessage.characterSet = dlh.getCodedCharSetId();
            newMessage.write(rest);
            message.seek(0);
            return newMessage;
        } catch (IOException | RuntimeException ex) {
            Logger.getLogger(MQUtility.class.getName()).log(Level.SEVERE, null, ex);
        }
        return message;
    }
    
    public static void SortOutDLQ(MQQueueManager queueManager, String queueName){
        HashMap<String, ArrayList<MQMessage>> msgHash = new HashMap<>();
        MQQueue queue = null;
        try {
            queue = queueManager.accessQueue(queueName, CMQC.MQOO_INQUIRE | CMQC.MQOO_BROWSE);
            int queueDepth = queue.getCurrentDepth();
            int index = queueDepth;
            MQMessage message = new MQMessage();
            MQGetMessageOptions options = new MQGetMessageOptions();
            options.options = CMQC.MQGMO_BROWSE_NEXT;
            //options.matchOptions = MQConstants.MQMO_MATCH_MSG_ID  | MQConstants.MQMO_MATCH_CORREL_ID;
            while(index > 0){ 
                message = new MQMessage();
                try{
                    queue.get(message, options);
                    try {
                        MQHeaderList list = new MQHeaderList(message, true);
                        if(list.size() > 0 && list.indexOf("MQDLH") >= 0){
                            MQDLH dlh = (MQDLH) list.get(list.indexOf("MQDLH"));
                            String targetName = dlh.getDestQMgrName().trim() + "." + dlh.getDestQName().trim();
                            message.seek(0);
                            if(msgHash.containsKey(targetName)){
                                ((ArrayList<MQMessage>)msgHash.get(targetName)).add(message);
                            }
                            else{
                                ArrayList<MQMessage> msgList = new ArrayList<>();
                                msgList.add(message);
                                msgHash.put(targetName, msgList);
                            }
                        }
                    } catch (MQDataException ex) {
                        Logger.getLogger(MQUtility.class.getName()).log(Level.SEVERE, null, ex);
                    } catch (IOException ex) {
                        Logger.getLogger(MQUtility.class.getName()).log(Level.SEVERE, null, ex);
                    } catch(Exception ex){

                    }
                }
                catch(MQException ex){
                    if(ex.getReason() == MQConstants.MQRC_NO_MSG_AVAILABLE){
                        break;
                    }
                    else{
                        LogWriter.WriteToLog("MQUtility", "GetMessageList", ex);
                        throw ex;
                    }
                }
                index--;
            }            
        } catch (MQException ex) {
            LogWriter.WriteToLog("MQUtility", "GetMessageList", ex);
            closeQueue(queue);
        }
        closeQueue(queue);
        
        Iterator it = msgHash.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry pair = (Map.Entry)it.next();
            String fileName = (String)pair.getKey();
            ArrayList<MQMessage> list = (ArrayList<MQMessage>)pair.getValue();
//            it.remove(); // avoids a ConcurrentModificationException
            try {
                BackupMessageToFile(fileName, list, true, false);
            } catch (Exception ex) {
                Logger.getLogger(MQUtility.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        
    }
        
    //Msc
    
    /** Headers read from the start of a message, and why the reading stopped early (null when it did not). */
    public static class MessageHeaders {
        public final List<MQHeader> Headers = new ArrayList<>();
        public String Problem;
        /** Format and encoding of the data after the headers. */
        public String DataFormat;
        public int DataEncoding;
    }

    /** PCF commands, responses and events: binary data, decoded by DecodePcf. */
    public static boolean IsPcfFormat(String format){
        return MQConstants.MQFMT_ADMIN.equals(format) || MQConstants.MQFMT_EVENT.equals(format) || MQConstants.MQFMT_PCF.equals(format);
    }

    /**
     * The MQ headers at the start of the message (dead-letter header, RFH2...), read one at a time: a
     * header that cannot be read, e.g. a format that announces a header the data does not contain,
     * ends the list and its bytes count as data, so the headers before it (the DLH) are still shown.
     * Afterwards the message is positioned at the start of the data.
     */
    public static List<MQHeader> ReadHeaders(MQMessage message){
        return ParseHeaders(message).Headers;
    }

    public static MessageHeaders ParseHeaders(MQMessage message){
        MessageHeaders result = new MessageHeaders();
        result.DataFormat = message.format;
        result.DataEncoding = message.encoding;
        int dataOffset = 0;
        try {
            message.seek(0);
            MQHeaderIterator iterator = new MQHeaderIterator(message);
            if(MQConstants.MQFMT_DEAD_LETTER_HEADER.equals(message.format)){
                // Read from its own bytes: the MQMD encoding and CCSID may not match how it was written.
                // A plain stream, because from an MQMessage the headers are always read with its MQMD values.
                byte[] bytes = new byte[message.getMessageLength()];
                message.seek(0);
                message.readFully(bytes);
                MQDLH dlh = readDeadLetterHeader(bytes);
                if(dlh != null){
                    result.Headers.add(dlh);
                    dataOffset = dlh.size();
                    result.DataFormat = dlh.getFormat();
                    result.DataEncoding = dlh.getEncoding();
                    iterator = new MQHeaderIterator(new DataInputStream(new ByteArrayInputStream(bytes, dataOffset, bytes.length - dataOffset)),
                            dlh.getFormat(), dlh.getEncoding(), dlh.getCodedCharSetId());
                }
                else{
                    message.seek(0);
                }
            }
            // PCF data is not a header: the iterator would read its parameters as headers and leave no data
            while(!IsPcfFormat(result.DataFormat) && iterator.hasNext()){
                MQHeader header = iterator.nextHeader();
                result.Headers.add(header);
                dataOffset += header.size();
                if(header instanceof MQChainable){
                    result.DataFormat = ((MQChainable) header).nextFormat();
                    result.DataEncoding = ((MQChainable) header).nextEncoding();
                }
            }
        } catch (IOException | RuntimeException ex) {
            Throwable cause = ex;
            while(cause.getCause() != null && cause.getCause() != cause){
                cause = cause.getCause();
            }
            result.Problem = cause.getMessage() != null ? cause.getMessage() : cause.toString();
            LogWriter.WriteToLog("MQUtility", "ReadHeaders (data after " + result.Headers.size() + " headers)", ex);
        }
        if(result.Problem == null && result.Headers.isEmpty() && MQConstants.MQFMT_DEAD_LETTER_HEADER.equals(message.format)){
            result.Problem = "the format is MQDEAD but the data does not start with a dead-letter header";
        }
        try {
            message.seek(dataOffset);
        } catch (IOException ex) {
            Logger.getLogger(MQUtility.class.getName()).log(Level.SEVERE, null, ex);
        }
        return result;
    }

    /**
     * The dead-letter header at the start of the message, read with the encoding and character set its
     * own bytes show: "DLH " in ASCII or EBCDIC, version 1 in either byte order. Null when it is not there.
     */
    private static MQDLH readDeadLetterHeader(byte[] start) throws IOException {
        if(start.length < 8){
            return null;
        }
        int ccsid;
        if(start[0] == 'D' && start[1] == 'L' && start[2] == 'H' && start[3] == ' '){
            ccsid = 819;
        }
        else if((start[0] & 0xFF) == 0xC4 && (start[1] & 0xFF) == 0xD3 && (start[2] & 0xFF) == 0xC8 && (start[3] & 0xFF) == 0x40){
            ccsid = 500;
        }
        else{
            return null;
        }
        boolean bigEndian = start[4] == 0 && start[7] != 0;
        int encoding = bigEndian ? (ccsid == 500 ? 785 : 273) : 546;
        try{
            return new MQDLH(new DataInputStream(new ByteArrayInputStream(start)), encoding, ccsid);
        }catch(MQDataException ex){
            throw new IOException("dead-letter header not valid: " + ex.getMessage(), ex);
        }
    }

    /**
     * Offset, hexadecimal and ASCII of the bytes, 16 per line, like a hex editor; at most max bytes
     * (a line at the end says how many are left out).
     */
    public static String HexDump(byte[] bytes, int max){
        int count = Math.min(bytes.length, max);
        StringBuilder dump = new StringBuilder(count * 5);
        for(int offset = 0; offset < count; offset += 16){
            dump.append(String.format("%08X  ", offset));
            StringBuilder ascii = new StringBuilder(16);
            for(int i = 0; i < 16; i++){
                if(offset + i < count){
                    int value = bytes[offset + i] & 0xFF;
                    dump.append(String.format("%02X ", value));
                    ascii.append(value >= 0x20 && value < 0x7F ? (char) value : '.');
                }
                else{
                    dump.append("   ");
                }
                if(i == 7){
                    dump.append(' ');
                }
            }
            dump.append(" |").append(ascii).append("|\n");
        }
        if(bytes.length > count){
            dump.append("... ").append(bytes.length - count).append(" more bytes\n");
        }
        return dump.toString();
    }

    /** False when the text has only spaces, control characters (e.g. binary zeros) or undecodable bytes. */
    public static boolean HasVisibleText(String text){
        return text != null && text.codePoints().anyMatch(c -> !Character.isWhitespace(c) && !Character.isISOControl(c)
                && c != 0xFFFD && Character.getType(c) != Character.FORMAT);
    }

    /**
     * True for data that is not text: nothing visible, or control characters other than tab and line
     * breaks (binary zeros, packed numbers...). It is better shown as bytes than as text.
     */
    public static boolean IsBinary(String text){
        return !HasVisibleText(text) || text.chars().anyMatch(c -> Character.isISOControl(c) && c != '\t' && c != '\n' && c != '\r');
    }

    /** The data column of the message list: the start of the text, or of the bytes for binary data. */
    private static String listPreview(MQMessage message, boolean truncated){
        // PCF is decoded only whole; a truncated one is shown as data
        String pcf = truncated ? null : DecodePcf(message);
        if(pcf != null){
            // The first line: type and command
            return "PCF " + pcf.substring(0, pcf.indexOf('\n'));
        }
        String text = GetMessageStringContent(message, 4096);
        if(IsBinary(text)){
            ReadHeaders(message);
            try{
                byte[] bytes = new byte[Math.min(24, message.getDataLength())];
                message.readFully(bytes);
                return bytes.length == 0 ? "" : "hex " + BytesToHex(bytes);
            }catch(IOException ex){
                return "";
            }
        }
        String start = text.stripLeading();
        return start.length() > messageListDataLength ? start.substring(0, messageListDataLength) : start;
    }

    /**
     * PCF data (command, response or event, also behind a DLH) as text, one parameter per line with
     * the names of the MQ constants. Null when the data is not PCF or cannot be read as PCF.
     */
    public static String DecodePcf(MQMessage message){
        MessageHeaders headers = ParseHeaders(message);
        if(!IsPcfFormat(headers.DataFormat)){
            return null;
        }
        try{
            byte[] bytes = new byte[message.getDataLength()];
            message.readFully(bytes);
            // Read with the encoding of the data: the MQMD one may describe a header before it
            MQMessage data = new MQMessage();
            data.encoding = headers.DataEncoding;
            data.write(bytes);
            data.seek(0);
            PCFMessage pcf = new PCFMessage(data);
            StringBuilder text = new StringBuilder();
            text.append(constantName(pcf.getType(), "MQCFT_.*")).append("  ").append(constantName(pcf.getCommand(), "MQCMD_.*")).append('\n');
            text.append("Completion ").append(MQConstants.lookupCompCode(pcf.getCompCode()))
                    .append(", reason ").append(MQConstants.lookupReasonCode(pcf.getReason())).append(" (").append(pcf.getReason()).append(")\n");
            text.append("Message sequence ").append(pcf.getMsgSeqNumber()).append(pcf.getControl() == MQConstants.MQCFC_LAST ? ", last" : ", not last").append("\n\n");
            Enumeration<?> parameters = pcf.getParameters();
            while(parameters.hasMoreElements()){
                PCFParameter parameter = (PCFParameter) parameters.nextElement();
                String name = parameter.getParameterName();
                text.append(String.format("%-36s ", name != null ? name : String.valueOf(parameter.getParameter())))
                        .append(pcfValue(parameter)).append('\n');
            }
            return text.toString();
        }catch(IOException | MQDataException | RuntimeException ex){
            LogWriter.WriteToLog("MQUtility", "DecodePcf", ex);
            return null;
        }finally{
            ReadHeaders(message);
        }
    }

    private static String constantName(int value, String pattern){
        String name = MQConstants.lookup(value, pattern);
        return name != null ? name : String.valueOf(value);
    }

    // Values of constants by name (e.g. MQQT_LOCAL), strings without the padding, lists in brackets
    private static String pcfValue(PCFParameter parameter){
        Object value = parameter.getValue();
        if(value instanceof String){
            return ((String) value).trim();
        }
        if(value instanceof String[]){
            List<String> items = new ArrayList<>();
            for(String item : (String[]) value){
                items.add(item.trim());
            }
            return items.toString();
        }
        if(value instanceof byte[]){
            return BytesToHex((byte[]) value);
        }
        if(value instanceof int[]){
            return java.util.Arrays.toString((int[]) value);
        }
        if(value instanceof long[]){
            return java.util.Arrays.toString((long[]) value);
        }
        String decoded = parameter.decodeValue();
        return decoded != null && !decoded.isEmpty() ? decoded : String.valueOf(value);
    }

    /** The first bytes of the message in hexadecimal, to show what it contains when it cannot be read. */
    public static String FirstBytes(MQMessage message, int count){
        try{
            message.seek(0);
            byte[] bytes = new byte[Math.min(count, message.getMessageLength())];
            message.readFully(bytes);
            message.seek(0);
            return BytesToHex(bytes);
        }catch(IOException ex){
            return "";
        }
    }

    public static <T extends MQHeader> T FindHeader(List<MQHeader> headers, Class<T> type){
        for(MQHeader header : headers){
            if(type.isInstance(header)){
                return type.cast(header);
            }
        }
        return null;
    }

    /** Length of the data after the headers; the message is positioned at its start. */
    public static int GetMessageContentLength(MQMessage message){
        int msglen = 0;
        try {
            msglen = message.getMessageLength();
            for(MQHeader header : ReadHeaders(message)){
                msglen = msglen - header.size();
            }
        } catch(Exception ex){
            Logger.getLogger(MQUtility.class.getName()).log(Level.SEVERE, null, ex);
        }
        return msglen;
    }
    
    /**
     * The data after the headers as text, in the character set the headers give for it: behind a DLH
     * (or RFH2) the MQMD CCSID describes the header, the data may be in another one (e.g. EBCDIC from
     * z/OS). Bytes that are not valid in that character set become replacement characters.
     */
    public static String GetMessageStringContent(MQMessage message, Integer length){
        try{
            int messageLength = message.getMessageLength();
            List<MQHeader> headers = ReadHeaders(message);
            for(MQHeader header : headers){
                messageLength -= header.size();
            }
            int requiredLen = length != null && length < messageLength ? length : messageLength;
            byte[] buff = new byte[Math.max(0, requiredLen)];
            message.readFully(buff);
            return new String(buff, DataCharset(message, headers));
        }catch(IOException ex){
            Logger.getLogger(MQUtility.class.getName()).log(Level.SEVERE, null, ex);
        }
        return "";
    }

    /**
     * Replaces the data of the message with the text and keeps its headers (DLH, RFH2...) as they
     * are, with the text in the character set they give for the data. A message without headers
     * becomes MQSTR. setDescriptor sets the edited MQMD values once the old data is cleared.
     */
    public static void ReplaceText(MQMessage message, String text, Runnable setDescriptor) throws IOException {
        List<MQHeader> headers = ReadHeaders(message);
        int headerLength = 0;
        for(MQHeader header : headers){
            headerLength += header.size();
        }
        byte[] headerBytes = new byte[headerLength];
        message.seek(0);
        message.readFully(headerBytes);
        message.clearMessage();
        setDescriptor.run();
        if(headerLength == 0){
            message.writeString(text);
            message.format = MQConstants.MQFMT_STRING;
            return;
        }
        message.write(headerBytes);
        message.write(text.getBytes(DataCharset(message, headers)));
    }

    /** Character set of the data after the headers: the last one a header gives, otherwise the MQMD one. */
    public static Charset DataCharset(MQMessage message, List<MQHeader> headers){
        int ccsid = message.characterSet;
        for(MQHeader header : headers){
            if(header instanceof MQChainable){
                // MQCCSI_INHERIT and the other special values (zero or negative) keep the previous one
                int next = ((MQChainable) header).nextCharacterSet();
                if(next > 0){
                    ccsid = next;
                }
            }
        }
        for(int candidate : new int[]{ccsid, message.characterSet}){
            try{
                return Charset.forName(CCSID.getCodepage(candidate));
            }catch(Exception ex){
                // Not known to Java: try the next one
            }
        }
        return StandardCharsets.UTF_8;
    }
    
    // test method
    public static Charset GetMessageCharset(MQMessage message){
        int a = CMQC.MQCCSI_Q_MGR;
        switch (message.characterSet){
            case 850 :
                return StandardCharsets.US_ASCII;
            case 819 :
                return StandardCharsets.ISO_8859_1;
            case 37 :
                return StandardCharsets.UTF_16BE;
            case 1200 :
                return StandardCharsets.UTF_16;
            case 1208 :
                return StandardCharsets.UTF_8;
        }
        return StandardCharsets.US_ASCII;
    }
    
//private
    private static MessageDetailModel turnToMessageModel(MQMessageListResult result, MQMessage message, int position){
        return turnToMessageModel(result, message, position, 0);
    }

    /**
     * Length of the message data (without the message properties) also when only its start was read:
     * the properties take their part of the buffer, so the data is the total length minus that part.
     * truncatedAt: the buffer length of a truncated read, 0 when the whole message was read.
     */
    static int DataLength(int totalLength, int bytesRead, int truncatedAt){
        return truncatedAt > 0 ? totalLength - (truncatedAt - bytesRead) : bytesRead;
    }

    private static MessageDetailModel turnToMessageModel(MQMessageListResult result, MQMessage message, int position, int truncatedAt){
        MessageDetailModel model = result.new MessageDetailModel();
        model.MessageId = message.messageId;
        model.CorrelationId = message.correlationId;
        model.ApplicationIdentifyData = message.applicationIdData.trim();
        model.ApplicationOriginalData = message.applicationOriginData.trim();
        try {
            // The length read, wherever the message is positioned (the search has already read it)
            model.DataLength = Integer.toString(DataLength(message.getTotalMessageLength(), message.getMessageLength(), truncatedAt));
        } catch (IOException ex) {
            model.DataLength = "0";
            Logger.getLogger(MQUtility.class.getName()).log(Level.SEVERE, null, ex);
        }
        model.Encoding = Integer.toString(message.encoding).trim();
        model.Expiry = message.expiry == MQConstants.MQEI_UNLIMITED? "Unlimited" : DateTimeHelper.GetCurrentAddSecondTimeStamp(message.expiry / 10);
        model.Format = message.format.trim();
        model.MessageType = getMQMEessageType(message.messageType);
        model.Persistence = message.persistence == 0 ? "Not persistence" : "Persistence";
        model.Priority = Integer.toString(message.priority).trim();
        model.PutApplicationName = message.putApplicationName.trim();
        model.PutDateTime =  message.putDateTime != null ? DateTimeHelper.GetCustomTimeStamp(message.putDateTime.getTime()) : null;
        model.Position = Integer.toString(position).trim();
        model.ReplyToQueue = message.replyToQueueName.trim();
        model.ReplyToQueueManager = message.replyToQueueManagerName.trim();
        model.TotalLength = Integer.toString(message.getTotalMessageLength()).trim();
        model.UserIdentifier = message.userId.trim();
        model.SequenceNumber = message.messageSequenceNumber;
        model.AccountToken = BytesToHex(message.accountingToken);
        model.MessageIdString = BytesToHex(message.messageId);
        model.Offset = message.offset;
        model.MessageData = listPreview(message, truncatedAt > 0);
        //model.MessageFullData = GetMessageStringContent(message, null);
        model.GroupIDString = BytesToHex(message.groupId);
        model.BackoutCount = message.backoutCount;
        return model;
    }
    
    private static String getMQMEessageType(int type){
        switch(type){
            case CMQC.MQMT_DATAGRAM :
                return "Datagram";
            case CMQC.MQMT_REQUEST:
                return "Request";
            case CMQC.MQMT_REPLY:
                return "Reply";
            case CMQC.MQMT_REPORT:
                return "Report";
            default:
                return Integer.toString(type);
        }
    }

    private static void copyMessageMQMD(MQMessage newMessage, MQMessage oldMessage){
        for(Field field : oldMessage.getClass().getFields()){
            if(Modifier.isPublic(field.getModifiers()) && !Modifier.isStatic(field.getModifiers())){
                try {
                    String name = field.getName();
                    Object value = field.get(oldMessage);
                    newMessage.getClass().getField(name).set(newMessage, value);
                } catch (Exception ex) {
                    continue;
                }
            }
        }
    }

    private static void removeFile(String filePath){
        File file = new File(filePath);
        if(file.exists()){
            file.delete();
        }
    }
    
    private static void closeQueue(MQQueue queue){
        if(queue != null){
            try {
                queue.close();
            } catch (MQException ex) {
                LogWriter.WriteToLog("MQUtility", "removeFile",ex);
            }
        }
    }
       
    /**
     * Gets or browses the message of a list row. Its message token finds exactly that message, also when
     * several messages have the same message and correlation id (applications that set the message id
     * themselves). Without a token, or when the queue manager does not accept it, the first message with
     * the two ids is used. Afterwards the options match the ids of the message object, as before.
     */
    private static void getSelectedMessage(MQQueue queue, MQMessage message, MQGetMessageOptions options, MQMessageIdModel id, Integer maxSize) throws MQException{
        int getOptions = options.options;
        try{
            if(id.MessageToken != null){
                message.messageId = new byte[CMQC.MQ_MSG_ID_LENGTH];
                message.correlationId = new byte[CMQC.MQ_CORREL_ID_LENGTH];
                options.matchOptions = MQConstants.MQMO_MATCH_MSG_TOKEN;
                options.msgToken = id.MessageToken.clone();
                if((getOptions & CMQC.MQGMO_BROWSE_NEXT) != 0){
                    // The token finds the message wherever the browse cursor is
                    options.options = (getOptions & ~CMQC.MQGMO_BROWSE_NEXT) | CMQC.MQGMO_BROWSE_FIRST;
                }
                try{
                    getWithSize(queue, message, options, maxSize);
                    return;
                }
                catch(MQException ex){
                    if(ex.getReason() == MQConstants.MQRC_NO_MSG_AVAILABLE || ex.getReason() == MQConstants.MQRC_TRUNCATED_MSG_ACCEPTED){
                        throw ex;
                    }
                    LogWriter.WriteToLog("MQUtility", "getSelectedMessage (token not accepted, using the ids)", ex);
                    options.options = getOptions;
                }
            }
            message.messageId = id.MessageId;
            message.correlationId = id.CorrelationdId;
            options.matchOptions = MQConstants.MQMO_MATCH_MSG_ID  | MQConstants.MQMO_MATCH_CORREL_ID;
            getWithSize(queue, message, options, maxSize);
        }
        finally{
            options.options = getOptions;
            options.matchOptions = MQConstants.MQMO_MATCH_MSG_ID  | MQConstants.MQMO_MATCH_CORREL_ID;
            options.msgToken = new byte[CMQC.MQ_MSG_TOKEN_LENGTH];
        }
    }

    private static void getWithSize(MQQueue queue, MQMessage message, MQGetMessageOptions options, Integer maxSize) throws MQException{
        if(maxSize != null){
            queue.get(message, options, maxSize);
        }
        else{
            queue.get(message, options);
        }
    }

    private static boolean setQueueCursor(MQQueue queue, int position){
        MQMessage message = new MQMessage();
        MQGetMessageOptions options = new MQGetMessageOptions();
        options.options = CMQC.MQGMO_BROWSE_NEXT | CMQC.MQGMO_ACCEPT_TRUNCATED_MSG;
        options.matchOptions = CMQC.MQMO_NONE;
        try {
            int queueDepth = queue.getCurrentDepth();
            position = position > queueDepth ? queueDepth : position;
            for(int i = 0; i < position - 1; i++){
                // Only the cursor moves: no data is read
                try{
                    queue.get(message, options, 0);
                }
                catch(MQException ex){
                    if(ex.getReason() != MQConstants.MQRC_TRUNCATED_MSG_ACCEPTED){
                        throw ex;
                    }
                }
                resetMessage(message);
            }
        }
        catch (MQException ex) {
            Logger.getLogger(MQUtility.class.getName()).log(Level.SEVERE, null, ex);
            return false;
        } 
        return true;
    }
    
    private static void resetMessage(MQMessage message){
        try {
            message.clearMessage();
            message.messageId = MQConstants.MQMI_NONE;
            message.correlationId = MQConstants.MQMI_NONE;
        } catch (IOException ex) {
            Logger.getLogger(MQUtility.class.getName()).log(Level.SEVERE, null, ex);
            message = new MQMessage();
        }

    }
    
    // One file per destination of the dead-letter messages (developer tool, see SortOutDLQ)
    private static void BackupMessageToFile(String fileName, ArrayList<MQMessage> messages, boolean isCompress, boolean removeDLH) throws Exception{
        if(messages.isEmpty()){
            throw new Exception("Queue depth is 0");
        }
        String path = fileName + "." + messages.size() + "." + (removeDLH ? "NoDLH" : "DLH") + ".msg";
        try(MessageFile.Writer writer = new MessageFile.Writer(path, isCompress, fileName, null)){
            for(MQMessage message : messages){
                writer.Write(removeDLH ? RemoveMQDLH(message) : message);
            }
            writer.Finish();
        }
    }
    
    //try to get the current system encoding
    public static String getDefaultCharSet() {
    	OutputStreamWriter writer = new OutputStreamWriter(new ByteArrayOutputStream());
    	String enc = writer.getEncoding();
    	return enc;
    }
}
