/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package MQApi.Connection;

import MQApi.Logs.LogWriter;
import MQApi.Models.Query.ConnectionDetailModel;
import MQApi.PCF.PCFAgentPool;
import com.ibm.mq.MQEnvironment;
import com.ibm.mq.MQException;
import com.ibm.mq.MQExternalSecurityExit;
import com.ibm.mq.MQQueue;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.CMQC;
import com.ibm.mq.constants.MQConstants;
import java.util.Collections;
import java.util.Hashtable;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.Properties;
import javax.swing.JOptionPane;

/**
 *
 * @author jzhou
 */
public class MQConnection {
     public static MQQueueManager GetMQQueueManager(ConnectionDetailModel connectionDetail) throws MQException{
        MQQueueManager queueManager = null; 
        try{
            String userId = System.getProperty("user.name");
            Properties properties = new Properties();
            properties.put(MQConstants.HOST_NAME_PROPERTY, connectionDetail.Host);
            properties.put(MQConstants.PORT_PROPERTY, Integer.parseInt(connectionDetail.Port));
            properties.put(MQConstants.CHANNEL_PROPERTY, connectionDetail.Channel);
            properties.put("transport", "MQSeries");
            //properties.put(MQConstants.APPNAME_PROPERTY, "MQ Admin Tool");
            if(connectionDetail.User != null && !connectionDetail.User.isEmpty() && connectionDetail.IsNoPassword()){
                // Only the user id is sent, as for a connection without user: the queue manager
                // (CHCKCLNT OPTIONAL or NONE, channel authentication rules) decides what it is allowed
                properties.put(MQConstants.USER_ID_PROPERTY, connectionDetail.User);
            }
            else if(connectionDetail.User != null && !connectionDetail.User.isEmpty()){
                // The password is either saved with the connection or typed by the user for this session
                String password = SessionPasswords.Resolve(connectionDetail);
                if(password == null){
                    throw new MQException(MQConstants.MQCC_FAILED, MQConstants.MQRC_NOT_AUTHORIZED, "Password not entered");
                }
                properties.put(MQConstants.USE_MQCSP_AUTHENTICATION_PROPERTY, true);
                properties.put(MQConstants.USER_ID_PROPERTY, connectionDetail.User);
                properties.put(MQConstants.PASSWORD_PROPERTY, password);               
            }
            else{
                properties.put(MQConstants.USER_ID_PROPERTY, userId);
            }
            if(connectionDetail.IsTlsEnabled()){
                TlsSettings.Apply(connectionDetail, properties);
            }
            try{
                queueManager = new MQQueueManager(connectionDetail.ConnectedQueueManager(), properties);
            }catch(MQException ex){
                // The security exit is for queue managers older than MQ 7, which do not support TLS here either.
                // Retrying cannot help an unreachable host, and after a refused password it would count
                // as a second failed login (accounts get locked twice as fast).
                if(connectionDetail.IsTlsEnabled()
                        || ex.getReason() == MQConstants.MQRC_NOT_AUTHORIZED
                        || ex.getReason() == MQConstants.MQRC_HOST_NOT_AVAILABLE
                        || ex.getReason() == MQConstants.MQRC_Q_MGR_NOT_AVAILABLE){
                    throw ex;
                }
                properties.put("securityExit", new SecurityExit(null, null)); //for MQ ver < 7
                queueManager = new MQQueueManager(connectionDetail.ConnectedQueueManager(), properties);
            }
            if(connectionDetail.IsRemote()){
                remoteTargets.put(queueManager, connectionDetail.QueueManager.trim());
                checkRemoteAnswers(queueManager, connectionDetail);
                return queueManager;
            }
            MQQueue commandQueue = queueManager.accessQueue(queueManager.getCommandInputQueueName(), CMQC.MQOO_INQUIRE);
            try{
                if(commandQueue.getOpenInputCount() == 0){
                    throw new MQException(2, 1, "Command server is not running on this queue manager");
                }
            }finally{
                commandQueue.close();
            }
            return queueManager;
        }catch(MQException ex){
            if(queueManager != null){
                queueManager.disconnect();
                queueManager.close();
            }
            if(ex.getReason() == MQConstants.MQRC_NOT_AUTHORIZED){
                // Wrong password typed by the user: ask again at the next connection
                SessionPasswords.Forget(connectionDetail);
            }
            throw ex;
        }
    }   

    // Connections to an intermediate queue manager that administer another one, with its name
    private static final Map<MQQueueManager, String> remoteTargets = Collections.synchronizedMap(new WeakHashMap<>());
    private static final int REMOTE_CHECK_SECONDS = 20;

    /** The queue manager administered through this connection: another one when it is remote. */
    public static String QueueManagerName(MQQueueManager queueManager) throws MQException{
        String target = RemoteTarget(queueManager);
        return target != null ? target : queueManager.getName();
    }

    /** Name of the queue manager administered through this connection to an intermediate one; null for a direct connection. */
    public static String RemoteTarget(MQQueueManager queueManager){
        return queueManager == null ? null : remoteTargets.get(queueManager);
    }

    public static boolean IsRemote(MQQueueManager queueManager){
        return RemoteTarget(queueManager) != null;
    }

    // The connection is only useful if the remote command server answers: says what to check when it does not
    private static void checkRemoteAnswers(MQQueueManager queueManager, ConnectionDetailModel connectionDetail) throws MQException{
        String target = connectionDetail.QueueManager.trim();
        String via = connectionDetail.ConnectedQueueManager();
        try{
            MQApi.PCF.MQPCF.PingRemote(queueManager, REMOTE_CHECK_SECONDS);
        }catch(Exception ex){
            LogWriter.WriteToLog("MQConnection", "checkRemoteAnswers", ex);
            // The first MQ reason code in the chain (the PCF agent wraps them in MQDataException)
            int reason = MQConstants.MQRC_NO_MSG_AVAILABLE;
            for(Throwable cause = ex; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()){
                if(cause instanceof MQException){
                    reason = ((MQException) cause).getReason();
                    break;
                }
                if(cause instanceof com.ibm.mq.headers.MQDataException){
                    reason = ((com.ibm.mq.headers.MQDataException) cause).getReason();
                    break;
                }
            }
            String detail = reason == MQConstants.MQRC_NO_MSG_AVAILABLE
                    ? "no answer within " + REMOTE_CHECK_SECONDS + " seconds."
                    : MQApi.Utilities.MQUtility.getMQReturnMessage(MQConstants.MQCC_FAILED, reason);
            throw new MQException(MQConstants.MQCC_FAILED, reason, target + " does not answer through " + via + ": " + detail
                    + "\nCheck that " + via + " has a transmission queue or route to " + target + ", that the channels between them"
                    + " are running in both directions and that the command server of " + target + " is running.");
        }
    }

    public static void DisconnectMQQueueManager(MQQueueManager queueManager){
        if(queueManager == null){
            return;
        }
        PCFAgentPool.ReleaseAll(queueManager);
        if(!queueManager.isConnected()){
            return;
        }
        try{
            queueManager.disconnect();
        }catch(MQException ex){
            LogWriter.WriteToLog("MQConnection", "DisconnectMQQueueManager", ex);
        }
    }
}
