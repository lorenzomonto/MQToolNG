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
import java.util.Hashtable;
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
            if(connectionDetail.User != null && !connectionDetail.User.isEmpty()){
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
            try{
                queueManager = new MQQueueManager(connectionDetail.QueueManager, properties);
            }catch(MQException ex){
                // Retrying with the exit cannot help when the host is not reachable, it only doubles the wait
                if(ex.getReason() == MQConstants.MQRC_HOST_NOT_AVAILABLE || ex.getReason() == MQConstants.MQRC_Q_MGR_NOT_AVAILABLE){
                    throw ex;
                }
                properties.put("securityExit", new SecurityExit(null, null)); //for MQ ver < 7
                queueManager = new MQQueueManager(connectionDetail.QueueManager, properties);
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
