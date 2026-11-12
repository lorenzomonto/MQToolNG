/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package MQApi.PCF;
import MQApi.QueryModel.MQChannelListResult;
import MQApi.QueryModel.MQChannelStatusListResult;
import MQApi.QueryModel.MQQueueListResult;
import MQApi.QueryModel.MQQueuePropertyModel;
import MQApi.QueryModel.MQCommandResult;
import MQApi.QueryModel.MQNameListResult;
import MQApi.QueryModel.MQChannelStatusModel;
import MQApi.*;
import MQApi.Connection.MQConnection;
import MQApi.Enums.*;
import MQApi.Logs.LogWriter;
import MQApi.Models.ToolChannelStatusModel;
import MQApi.Models.ChannelStatusValueModel;
import MQApi.Models.Query.ConnectionDetailModel;
import MQApi.QueryModel.DetailModelCore;
import MQApi.QueryModel.MQChannelAuthListResult;
import MQApi.QueryModel.MQChannelAuthListResult.ChannelAuthDetailModel;
import MQApi.Result.Annotations.MQObjectListtAnnotation;
import MQApi.Result.Annotations.MQObjectPropertyAnnotation;
import MQApi.QueryModel.MQChannelListResult.ChannelDetailModel;
import MQApi.QueryModel.MQChannelPropertyModel;
import MQApi.QueryModel.MQChannelStatusListResult.ChannelStatusModel;
import MQApi.QueryModel.MQPubListResult;
import MQApi.QueryModel.MQPubListResult.PubDetailModel;
import MQApi.QueryModel.MQQueueListResult.QueueDetailModel;
import MQApi.QueryModel.MQQueueStatusHandleListResult;
import MQApi.QueryModel.MQQueueStatusHandleListResult.QueueStatusHandleDetailModel;
import MQApi.QueryModel.MQQueueStatusListResult;
import MQApi.QueryModel.MQQueueStatusListResult.QueueStatusDetailModel;
import MQApi.QueryModel.MQSubListResult;
import MQApi.QueryModel.MQSubListResult.SubDetailModel;
import MQApi.Utilities.MQUtility;
import com.ibm.mq.MQException;
import com.ibm.mq.MQMessage;
import com.ibm.mq.MQQueue;

import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.CMQC;
import java.io.IOException;

import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.headers.MQDataException;
import com.ibm.mq.headers.pcf.PCFException;
import com.ibm.mq.headers.pcf.PCFMessage;
import com.ibm.mq.headers.pcf.PCFMessageAgent;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Hashtable;
import java.util.logging.Level;
import java.util.logging.Logger;
/**
 *
 * @author jzhou
 */
public class MQPCF {
    private static MQQueueListResult MQQueueListResultCache = null;
    private static MQChannelListResult MQChannelListResultCache = null;
    private static MQChannelAuthListResult MQChannelAuthListResultCache = null;
    private static MQPubListResult MQPubListResultCache = null;
    private static MQSubListResult MQSubListResultCache = null;

    //Queue   
    public static MQNameListResult GetQueueNameList(ConnectionDetailModel connectionDetail, QueueType type, String querySring){
     
        MQNameListResult result = new MQNameListResult();       
        MQQueueManager queueManager = null;
        try {
            queueManager = MQConnection.GetMQQueueManager(connectionDetail);
            int queueType = ConstantConverter.ConvertQueueTypeToConstant(type);
            PCFMessageAgent agent = getPCFMessageAgent(queueManager);;
            PCFMessage pcfCmd = new PCFMessage(MQConstants.MQCMD_INQUIRE_Q_NAMES);
            pcfCmd.addParameter(MQConstants.MQCA_Q_NAME, querySring);
            pcfCmd.addParameter(MQConstants.MQIA_Q_TYPE, queueType);
            PCFMessage[] pcfResponse = null;
            pcfResponse = agent.send(pcfCmd);
            String[] names = (String[]) pcfResponse[0].getParameterValue(MQConstants.MQCACF_Q_NAMES);
            result.NameList = new ArrayList<String>();            
            result.NameList.addAll(Arrays.asList(names));
            //Object types =  pcfResponse[0].getParameterValue(MQConstants.MQIACF_Q_TYPES);
            result.QuerySuccess = true;
        }
        catch (Exception ex) {
                result.QuerySuccess = false;
                result.ErrorMessage = ex.getMessage();
        }
        finally {
            // This query opens its own connection, so close it (and its agent) when done
            MQConnection.DisconnectMQQueueManager(queueManager);
        }
        return result;
    }
    
    public static String ResolveAliasBaseQueueName(MQQueueManager queueManager, String queueName){
        try {
            MQQueuePropertyModel queueProperties = MQPCF.GetQueueProperties(queueManager, queueName);
            if(ConstantConverter.ConvertConstantToQueueType(queueProperties.Type)  == QueueType.Alias){
                return queueProperties.BaseObjectName;
            }  
            return queueName;
        } catch (MQDataException ex) {
            LogWriter.WriteToLog("MQPCF", "ResolveAliasBaseQueueName", ex);
            return null;
        } catch (IOException ex) {
            LogWriter.WriteToLog("MQPCF", "ResolveAliasBaseQueueName", ex);
            return null;
        }
    }
    
    public static void DeleteCRQOREXX(MQQueueManager queueManager){
        MQQueueListResult result = GetQueueList(queueManager, "SYSTEM.CSQOREXX*", null, true );
        if(result.QuerySuccess){
            for(QueueDetailModel model : result.DataModels ){
                DeleteQueue(queueManager, model.Name, model.Type);
            }
        }
    }
          
    public static MQQueueListResult GetQueueList(MQQueueManager queueManager, String queueNameFilter, QueueType[] type, boolean loadNewData){
        if(loadNewData == true || (MQQueueListResultCache == null || MQQueueListResultCache.QuerySuccess == false)){
            return MQPCF.GetQueueList(queueManager,queueNameFilter, type);
        }
        else{
            return MQQueueListResultCache;
        }
    }
    
    public static MQQueueListResult GetQueueList(MQQueueManager queueManager, String queueNameFilter, QueueType[] type ){
        MQQueueListResult result = new MQQueueListResult();
        PCFMessageAgent agent = null;
        try {
            agent = getPCFMessageAgent(queueManager);
            PCFMessage[] pcfResponse = sendForList(agent, attributes -> {
                PCFMessage pcfCmd = new PCFMessage(MQConstants.MQCMD_INQUIRE_Q); 
                pcfCmd.addParameter(MQConstants.MQCA_Q_NAME, queueNameFilter);
                pcfCmd.addParameter(MQConstants.MQIA_Q_TYPE, MQConstants.MQQT_ALL );
                if(attributes){
                    pcfCmd.addParameter(MQConstants.MQIACF_Q_ATTRS, listAttributes(QueueDetailModel.class, QueryType.QueueDetial));
                }
                return pcfCmd;
            });
            WriteToDetailModel(pcfResponse, result, QueueDetailModel.class, type);
            result.QuerySuccess = true;
        } catch (MQDataException ex) {
            result.QuerySuccess = false;
            result.ErrorMessage = MQUtility.getMQReturnMessage(ex.getCompCode(), ex.getReason());
            LogWriter.WriteToLog("MQPCF", "GetQueueList", ex);
        } catch (IOException ex) {
            result.QuerySuccess = false;
            result.ErrorMessage = ex.getMessage();
            LogWriter.WriteToLog("MQPCF", "GetQueueList", ex);
        } 
        MQQueueListResultCache = result;
        disconnectAgent(agent);        
        return result;
    }
    
    public static MQQueuePropertyModel GetQueueProperties(MQQueueManager queueManager, String name) throws MQDataException, IOException{
        MQQueuePropertyModel model = null;
        PCFMessageAgent agent = null;
        try {
            agent = getPCFMessageAgent(queueManager);;
            PCFMessage pcfCmd = new PCFMessage(MQConstants.MQCMD_INQUIRE_Q);        
            pcfCmd.addParameter(MQConstants.MQCA_Q_NAME, name);
            agent.setCheckResponses(false);
            PCFMessage[] pcfResponse = agent.send(pcfCmd);
            if(pcfResponse.length > 0 && pcfResponse[0].getCompCode() == 0){
                model = new MQQueuePropertyModel();
                readToPropertyModel(pcfResponse[0], model);
            }
        } catch (MQDataException ex) {
            disconnectAgent(agent);
            LogWriter.WriteToLog("MQPCF", "GetQueueProperties", ex);
            throw ex;
        } catch (IOException ex) {
            disconnectAgent(agent);
            LogWriter.WriteToLog("MQPCF", "GetQueueProperties", ex);
            throw ex;
        }
        disconnectAgent(agent);
        return model;
    }
        
    public static MQCommandResult UpdateQueueProperties(MQQueueManager queueManager, MQQueuePropertyModel model){
        return CreateOrModifyQueueProperties(queueManager, model, false);
    }
    
    public static MQCommandResult CreateQueue(MQQueueManager queueManager, MQQueuePropertyModel model){
        return CreateOrModifyQueueProperties(queueManager, model, true);
    }
           
    public static boolean CheckQueueExist(MQQueueManager queueManager, String name){
        try{
            MQQueuePropertyModel model = GetQueueProperties(queueManager, name);
            return model != null;
        }catch(Exception ex){
            return false;
        }
    }
          
    public static MQCommandResult ClearQueue(MQQueueManager queueManager, String queueName, boolean isAlias) {
        PCFMessageAgent agent = null;
        MQCommandResult result = new MQCommandResult();        
        try {
            int numMsgsToClear = MQPCF.GetQueueProperties(queueManager, queueName).CurrentQDepth;
            if(isAlias){
                queueName = MQPCF.ResolveAliasBaseQueueName(queueManager, queueName);
            }
            agent = getPCFMessageAgent(queueManager);
            PCFMessage pcfCmd = new PCFMessage(MQConstants.MQCMD_CLEAR_Q);
            pcfCmd.addParameter(MQConstants.MQCA_Q_NAME, queueName);
            PCFMessage[] pcfResponse = agent.send(pcfCmd);
            LogWriter.WriteActivityToLog("MQPCF (ClearQueue) : Removed " + numMsgsToClear + " msgs from " + queueName + "(" + queueManager.getName() + ")");
            assignCommandReturnResult(pcfResponse[0], result);
        } catch (MQDataException ex) {
            LogWriter.WriteToLog("MQPCF", "ClearQueue", ex);
            result.QuerySuccess = false;
            result.ErrorMessage = getMQReturnMessage(ex.getCompCode(), ex.getReason());
        } catch (IOException | MQException ex) {
            LogWriter.WriteToLog("MQPCF", "ClearQueue", ex);
            result.QuerySuccess = false;
            result.ErrorMessage = ex.getMessage();
        }
        disconnectAgent(agent);
        return result;
    }   
    
    public static MQCommandResult DeleteQueue(MQQueueManager queueManager, String queueName, QueueType type){
        MQCommandResult result = new MQCommandResult();
        PCFMessageAgent agent = null;
        try {
            agent = getPCFMessageAgent(queueManager);;
            PCFMessage pcfCmd = new PCFMessage(MQConstants.MQCMD_DELETE_Q);
            pcfCmd.addParameter(MQConstants.MQCA_Q_NAME, queueName);
            if(type == QueueType.Local){
                pcfCmd.addParameter(MQConstants.MQIACF_PURGE, MQConstants.MQPO_YES);
            }
            PCFMessage[] pcfResponse = agent.send(pcfCmd);
            assignCommandReturnResult(pcfResponse[0], result);            
        } catch (MQDataException ex) {
            LogWriter.WriteToLog("MQPCF", "DeleteQueue", ex);
            result.QuerySuccess = false;
            result.ErrorMessage = getMQReturnMessage(ex.getCompCode(), ex.getReason());
        } catch (IOException ex) {
            LogWriter.WriteToLog("MQPCF", "DeleteQueue", ex);
            result.QuerySuccess = false;
            result.ErrorMessage = ex.getMessage();
        }
        disconnectAgent(agent);
        return result;
        
    }
    
    public static MQQueueStatusHandleListResult GetQueueStatusHandleList(MQQueueManager queueManager, String nameFilter){
        MQQueueStatusHandleListResult result = new MQQueueStatusHandleListResult();
        PCFMessageAgent agent = null;
        try {
            agent = getPCFMessageAgent(queueManager);;
            PCFMessage pcfCmd = new PCFMessage(MQConstants.MQCMD_INQUIRE_Q_STATUS);        
            pcfCmd.addParameter(MQConstants.MQCA_Q_NAME, nameFilter);
            pcfCmd.addParameter(MQConstants.MQIACF_Q_STATUS_TYPE, MQConstants.MQIACF_Q_HANDLE);
            agent.setCheckResponses(false);
            PCFMessage[] pcfResponse = agent.send(pcfCmd);    
            if(pcfResponse.length > 0 && pcfResponse[0].getCompCode() != 2){
                WriteToDetailModel(pcfResponse, result, QueueStatusHandleDetailModel.class, null);
                result.QuerySuccess = true;
            }
            else{
                result.QuerySuccess = false;
                result.ErrorMessage = getMQReturnMessage(pcfResponse[0].getCompCode(), pcfResponse[0].getReason());
            }
        } catch (MQDataException ex) {
            result.QuerySuccess = false;
            result.ErrorMessage = MQUtility.getMQReturnMessage(ex.getCompCode(), ex.getReason());
            LogWriter.WriteToLog("MQPCF", "GetQueueStatusHandleList", ex);
        } catch (IOException ex) {
            result.QuerySuccess = false;
            result.ErrorMessage = ex.getMessage();
            LogWriter.WriteToLog("MQPCF", "GetQueueStatusHandleList", ex);
        }
        disconnectAgent(agent);
        return result;
    }
    
    public static MQQueueStatusListResult GetQueueStatusList(MQQueueManager queueManager, String nameFilter){
        MQQueueStatusListResult result = new MQQueueStatusListResult();
        PCFMessageAgent agent = null;
        try {
            agent = getPCFMessageAgent(queueManager);;
            PCFMessage pcfCmd = new PCFMessage(MQConstants.MQCMD_INQUIRE_Q_STATUS);        
            pcfCmd.addParameter(MQConstants.MQCA_Q_NAME, nameFilter);
            pcfCmd.addParameter(MQConstants.MQIACF_Q_STATUS_TYPE, MQConstants.MQIACF_Q_STATUS);
            agent.setCheckResponses(false);
            PCFMessage[] pcfResponse = agent.send(pcfCmd);    
            if(pcfResponse.length > 0 && pcfResponse[0].getCompCode() != 2){
                WriteToDetailModel(pcfResponse, result, QueueStatusDetailModel.class, null);
                result.QuerySuccess = true;
            }
            else{
                result.QuerySuccess = false;
                result.ErrorMessage = getMQReturnMessage(pcfResponse[0].getCompCode(), pcfResponse[0].getReason());
            }
        } catch (MQDataException ex) {
            result.QuerySuccess = false;
            result.ErrorMessage = MQUtility.getMQReturnMessage(ex.getCompCode(), ex.getReason());
            LogWriter.WriteToLog("MQPCF", "GetQueueStatusList", ex);
        } catch (IOException ex) {
            result.QuerySuccess = false;
            result.ErrorMessage = ex.getMessage();
            LogWriter.WriteToLog("MQPCF", "GetQueueStatusList", ex);
        }
        disconnectAgent(agent);
        return result;
    }
        
    //Channel
    public static MQChannelListResult GetChannelList(MQQueueManager queueManager, String channelNameFilter, ChannelType[] type, boolean loadNewData){
        if(loadNewData == true || (MQChannelListResultCache == null || MQChannelListResultCache.QuerySuccess == false)){
            return GetChannelList(queueManager, channelNameFilter, type);
        }
        else{
            return MQChannelListResultCache;
        }
    }
    
    public static MQChannelListResult GetChannelList(MQQueueManager queueManager, String channelNameFilter, ChannelType[] type){
        MQChannelListResult result = new MQChannelListResult();
        PCFMessageAgent agent = null;
        try {
            agent = getPCFMessageAgent(queueManager);;
            PCFMessage[] pcfResponseDetail = sendForList(agent, attributes -> {
                PCFMessage pcfCmdQueryDetail = new PCFMessage(MQConstants.MQCMD_INQUIRE_CHANNEL);        
                pcfCmdQueryDetail.addParameter(MQConstants.MQCACH_CHANNEL_NAME, channelNameFilter);
                pcfCmdQueryDetail.addParameter(MQConstants.MQIACH_CHANNEL_TYPE, MQConstants.MQCHT_ALL );
                if(attributes){
                    pcfCmdQueryDetail.addParameter(MQConstants.MQIACF_CHANNEL_ATTRS, listAttributes(ChannelDetailModel.class, QueryType.ChannelDetail));
                }
                return pcfCmdQueryDetail;
            });
            WriteToDetailModel(pcfResponseDetail, result, ChannelDetailModel.class, type);       
            MQChannelStatusListResult channelStatus = GetChannelStatusList(queueManager, "*", null, false);
            Hashtable<String, ChannelStatusValueModel> statusTable = new Hashtable<String, ChannelStatusValueModel>();
            WriteToHashtable(channelStatus.DataModels, statusTable);
            GetCombinedChannelDetailResult(result, statusTable);
            result.QuerySuccess = true;
        
        } catch (MQDataException ex) {
            result.QuerySuccess = false;
            result.ErrorMessage = MQUtility.getMQReturnMessage(ex.getCompCode(), ex.getReason());            
            LogWriter.WriteToLog("MQPCF", "GetChannelList", ex);
        } catch (IOException ex) {
            result.QuerySuccess = false;
            result.ErrorMessage = ex.getMessage();
            LogWriter.WriteToLog("MQPCF", "GetChannelList", ex);
        }
        MQChannelListResultCache = result;
        disconnectAgent(agent);
        
        return result;
    }
 
    public static MQChannelStatusListResult GetChannelStatusList(MQQueueManager queueManager, String nameFilter, ChannelType[] type, boolean getSaved){
        MQChannelStatusListResult result = new MQChannelStatusListResult();
        PCFMessageAgent agent = null;
        try {
            agent = getPCFMessageAgent(queueManager);;
            PCFMessage pcfCmd = new PCFMessage(MQConstants.MQCMD_INQUIRE_CHANNEL_STATUS);        
            pcfCmd.addParameter(MQConstants.MQCACH_CHANNEL_NAME, nameFilter);
            if(getSaved){
                pcfCmd.addParameter(MQConstants.MQIACH_CHANNEL_INSTANCE_TYPE, MQConstants.MQOT_SAVED_CHANNEL);
            }
            agent.setCheckResponses(false);
            PCFMessage[] pcfResponse = agent.send(pcfCmd);    
            if(pcfResponse.length > 0 && pcfResponse[0].getCompCode() != 2){
                WriteToDetailModel(pcfResponse, result, ChannelStatusModel.class, type);;
                result.QuerySuccess = true;
            }
            else{
                result.QuerySuccess = false;
                result.ErrorMessage = getMQReturnMessage(pcfResponse[0].getCompCode(), pcfResponse[0].getReason());
            }
        } catch (MQDataException ex) {
            result.QuerySuccess = false;
            result.ErrorMessage = MQUtility.getMQReturnMessage(ex.getCompCode(), ex.getReason());
            LogWriter.WriteToLog("MQPCF", "GetchannelStatusList", ex);
        } catch (IOException ex) {
            result.QuerySuccess = false;
            result.ErrorMessage = ex.getMessage();
            LogWriter.WriteToLog("MQPCF", "GetchannelStatusList", ex);
        }
        disconnectAgent(agent);
        return result;
    }
        
    public static MQChannelStatusModel GenerateChannelStatus(MQQueueManager queueManager, String querySring){
         MQChannelStatusModel result = new MQChannelStatusModel();
         PCFMessageAgent agent = null;
        try {           
            agent = getPCFMessageAgent(queueManager);;
            PCFMessage pcfCmd = new PCFMessage(MQConstants.MQCMD_INQUIRE_CHANNEL_STATUS);
            pcfCmd.addParameter(MQConstants.MQCACH_CHANNEL_NAME, querySring);
            PCFMessage[] pcfResponse = null;
            pcfResponse = agent.send(pcfCmd);
            result.QuerySuccess = true;
            result.ChannelStatusList = ConveryToChModel(pcfResponse);    
        } catch (Exception ex) {
            result.QuerySuccess = false;
            result.ErrorMessage = ex.getMessage();
            LogWriter.WriteToLog("MQPCF", "GenerateChannelStatus", ex);           
        }  
        disconnectAgent(agent); 
        return result;
    }   
    
    public static boolean CheckChannelExist(MQQueueManager queueManager, String name){
            try{
                MQChannelPropertyModel model = GetChannelProperties(queueManager, name);
                return model != null;
            }catch(Exception ex){
                return false;
            }
    }
    
    public static MQChannelPropertyModel GetChannelProperties(MQQueueManager queueManager, String name) throws MQDataException, IOException{
        MQChannelPropertyModel model = null;
        PCFMessageAgent agent = null;
        try {
            agent = getPCFMessageAgent(queueManager);;
            PCFMessage pcfCmd = new PCFMessage(MQConstants.MQCMD_INQUIRE_CHANNEL);        
            pcfCmd.addParameter(MQConstants.MQCACH_CHANNEL_NAME, name);
            agent.setCheckResponses(false);
            PCFMessage[] pcfResponse = agent.send(pcfCmd);
            if(pcfResponse.length > 0 && pcfResponse[0].getCompCode() == 0){
                model = new MQChannelPropertyModel();
                readToPropertyModel(pcfResponse[0], model);
            }
        
        } catch (MQDataException ex) {
            disconnectAgent(agent);
            LogWriter.WriteToLog("MQPCF", "GetChannelProperties", ex);
            throw ex;
        } catch (IOException ex) {
            disconnectAgent(agent);
            LogWriter.WriteToLog("MQPCF", "GetChannelProperties", ex);
            throw ex;
        }
        disconnectAgent(agent);
        return model;
    }
    
    public static MQCommandResult UpdateChannelProperties(MQQueueManager queueManager, MQChannelPropertyModel model){
        return CreateOrModifyChannelProperties(queueManager, model, false);
    }
    
    public static MQCommandResult CreateChannel(MQQueueManager queueManager, MQChannelPropertyModel model){
        return CreateOrModifyChannelProperties(queueManager, model, true);
    }
    
    public static MQCommandResult StartChannel(MQQueueManager queueManager, String name){
        MQCommandResult result = new MQCommandResult();
        PCFMessageAgent agent = null;
        try {
            agent = getPCFMessageAgent(queueManager);;
            PCFMessage pcfCmdQueryDetail = new PCFMessage(MQConstants.MQCMD_START_CHANNEL);
            pcfCmdQueryDetail.addParameter(MQConstants.MQCACH_CHANNEL_NAME, name);
            PCFMessage[] pcfResponse = agent.send(pcfCmdQueryDetail);
            assignCommandReturnResult(pcfResponse[0], result);
        } catch (MQDataException ex) {
            LogWriter.WriteToLog("MQPCF", "StartChannel", ex);
            result.QuerySuccess = false;
            result.ErrorMessage = getMQReturnMessage(ex.getCompCode(), ex.getReason());
        } catch (IOException ex) {
            LogWriter.WriteToLog("MQPCF", "StartChannel", ex);
            result.QuerySuccess = false;
            result.ErrorMessage = ex.getMessage();
        }
        disconnectAgent(agent);
        return result;
    }

    public static MQCommandResult StopChannel(MQQueueManager queueManager, String name, ChannelStatusType toStatus, ChannelStopType stopType, String queueManagerName, String connectionName){
        MQCommandResult result = new MQCommandResult();
        PCFMessageAgent agent = null;
        if(toStatus != ChannelStatusType.Inactive && toStatus != ChannelStatusType.Stopped){
            result.QuerySuccess = false;
            result.ErrorMessage = "Status error: must be Inactive or Stopped";
            return result;
        }
        try {
            agent = getPCFMessageAgent(queueManager);;
            agent.setCheckResponses(false);
            PCFMessage pcfCmdQueryDetail = new PCFMessage(MQConstants.MQCMD_STOP_CHANNEL);
            pcfCmdQueryDetail.addParameter(MQConstants.MQCACH_CHANNEL_NAME, name);
            pcfCmdQueryDetail.addParameter(MQConstants.MQIACH_CHANNEL_STATUS, toStatus == ChannelStatusType.Inactive ? MQConstants.MQCHS_INACTIVE : MQConstants.MQCHS_STOPPED);
            switch(stopType){
                case Force:
                    pcfCmdQueryDetail.addParameter(MQConstants.MQIACF_MODE, MQConstants.MQMODE_FORCE);
                    break;
                case Quiesec:
                    pcfCmdQueryDetail.addParameter(MQConstants.MQIACF_MODE, MQConstants.MQMODE_QUIESCE);
                    break;
                case Terminate:
                    pcfCmdQueryDetail.addParameter(MQConstants.MQIACF_MODE, MQConstants.MQMODE_TERMINATE);
                    break;
            }
            if(toStatus == ChannelStatusType.Inactive){
                if(queueManagerName != null && !queueManagerName.isEmpty()){
                    pcfCmdQueryDetail.addParameter(MQConstants.MQCA_Q_MGR_NAME, queueManagerName);
                }
                if(connectionName != null && !connectionName.isEmpty()){
                    pcfCmdQueryDetail.addParameter(MQConstants.MQCACH_CONNECTION_NAME, connectionName);
                }
            }
            PCFMessage[] pcfResponse = agent.send(pcfCmdQueryDetail);
            assignCommandReturnResult(pcfResponse[0], result);
        } catch (MQDataException ex) {
            LogWriter.WriteToLog("MQPCF", "StopChannel", ex);
            result.QuerySuccess = false;
            result.ErrorMessage = getMQReturnMessage(ex.getCompCode(), ex.getReason());
        } catch (IOException ex) {
            LogWriter.WriteToLog("MQPCF", "StopChannel", ex);
            result.QuerySuccess = false;
            result.ErrorMessage = ex.getMessage();
        }
        disconnectAgent(agent);         
        return result;
    }
    
    public static MQCommandResult ResetChannel(MQQueueManager queueManager, String name, Integer seq){
        MQCommandResult result = new MQCommandResult();
        PCFMessageAgent agent = null;
        try {
            agent = getPCFMessageAgent(queueManager);;
            PCFMessage pcfCmdQueryDetail = new PCFMessage(MQConstants.MQCMD_RESET_CHANNEL);
            pcfCmdQueryDetail.addParameter(MQConstants.MQCACH_CHANNEL_NAME, name);
            pcfCmdQueryDetail.addParameter(MQConstants.MQIACH_MSG_SEQUENCE_NUMBER, seq);
            PCFMessage[] pcfResponse = agent.send(pcfCmdQueryDetail);
            assignCommandReturnResult(pcfResponse[0], result);
        } catch (MQDataException ex) {
            LogWriter.WriteToLog("MQPCF", "ResetChannel", ex);
            result.QuerySuccess = false;
            result.ErrorMessage = getMQReturnMessage(ex.getCompCode(), ex.getReason());
        } catch (IOException ex) {
            LogWriter.WriteToLog("MQPCF", "ResetChannel", ex);
            result.QuerySuccess = false;
            result.ErrorMessage = ex.getMessage();
        }
        disconnectAgent(agent);
        return result;
    }
    
    public static MQCommandResult ResolveChannel(MQQueueManager queueManager, String name, ChannelResolveType type){
        MQCommandResult result = new MQCommandResult();
        PCFMessageAgent agent = null;
        try {
            agent = getPCFMessageAgent(queueManager);;
            PCFMessage pcfCmdQueryDetail = new PCFMessage(MQConstants.MQCMD_RESOLVE_CHANNEL);
            pcfCmdQueryDetail.addParameter(MQConstants.MQCACH_CHANNEL_NAME, name);
            pcfCmdQueryDetail.addParameter(MQConstants.MQIACH_IN_DOUBT, type == ChannelResolveType.Backout ? MQConstants.MQIDO_BACKOUT : MQConstants.MQIDO_COMMIT);
            PCFMessage[] pcfResponse = agent.send(pcfCmdQueryDetail);
            assignCommandReturnResult(pcfResponse[0], result);
        } catch (MQDataException ex) {
            LogWriter.WriteToLog("MQPCF", "ResolveChannel", ex);
            result.QuerySuccess = false;
            result.ErrorMessage = getMQReturnMessage(ex.getCompCode(), ex.getReason());
        } catch (IOException ex) {
            LogWriter.WriteToLog("MQPCF", "ResolveChannel", ex);
            result.QuerySuccess = false;
            result.ErrorMessage = ex.getMessage();
        }
        disconnectAgent(agent);
        return result;
    }
    
    public static MQCommandResult DeleteChannel (MQQueueManager queueManager, String channelName){
        MQCommandResult result = new MQCommandResult();
        PCFMessageAgent agent = null;
        try {
            agent = getPCFMessageAgent(queueManager);;
            PCFMessage pcfCmd = new PCFMessage(MQConstants.MQCMD_DELETE_CHANNEL);
            pcfCmd.addParameter(MQConstants.MQCACH_CHANNEL_NAME, channelName);
            PCFMessage[] pcfResponse = agent.send(pcfCmd);
            assignCommandReturnResult(pcfResponse[0], result);
            
        } catch (MQDataException ex) {
            LogWriter.WriteToLog("MQPCF", "DeleteChannel", ex);
            result.QuerySuccess = false;
            result.ErrorMessage = getMQReturnMessage(ex.getCompCode(), ex.getReason());
        } catch (IOException ex) {
            LogWriter.WriteToLog("MQPCF", "DeleteChannel", ex);
            result.QuerySuccess = false;
            result.ErrorMessage = ex.getMessage();
        }
        disconnectAgent(agent);
        return result;
        
    }
    
    //Channel Auth
    
    public static MQChannelAuthListResult GetChannelAuthList(MQQueueManager queueManager, String NameFilter, QueueType[] type, boolean loadNewData){
        if(loadNewData == true || (MQQueueListResultCache == null || MQQueueListResultCache.QuerySuccess == false)){
            return MQPCF.GetChannelAuthList(queueManager,NameFilter, type);
        }
        else{
            return MQChannelAuthListResultCache;
        }
    }
    
    public static MQChannelAuthListResult GetChannelAuthList(MQQueueManager queueManager, String queueNameFilter, QueueType[] type ){
        MQChannelAuthListResult result = new MQChannelAuthListResult();
        PCFMessageAgent agent = null;
        try {
            agent = getPCFMessageAgent(queueManager);;
            PCFMessage pcfCmd = new PCFMessage(MQConstants.MQCMD_INQUIRE_CHLAUTH_RECS);        
            pcfCmd.addParameter(MQConstants.MQCACH_CHANNEL_NAME, queueNameFilter);
            PCFMessage[] pcfResponse = agent.send(pcfCmd);        
            WriteToDetailModel(pcfResponse, result, ChannelAuthDetailModel.class, null); 
            result.QuerySuccess = true;
        } catch (MQDataException ex) {
            if(ex.getReason() == MQConstants.MQRCCF_CFH_COMMAND_ERROR ||ex.getReason() == MQConstants.MQRCCF_MQOPEN_FAILED ){
                result.QuerySuccess = true;
                disconnectAgent(agent);
                return result;
            }
            else{
                result.QuerySuccess = false;
                result.ErrorMessage = MQUtility.getMQReturnMessage(ex.getCompCode(), ex.getReason());
                LogWriter.WriteToLog("MQPCF", "GetChannelAuthList", ex);
            }
        } catch (IOException ex) {
            result.QuerySuccess = false;
            result.ErrorMessage = ex.getMessage();
            LogWriter.WriteToLog("MQPCF", "GetChannelAuthList", ex);
        } 
        MQChannelAuthListResultCache = result;
        disconnectAgent(agent);        
        return result;
    }
    
    //pub/sub
    
    public static MQPubListResult GetPubList(MQQueueManager queueManager, String NameFilter, QueueType[] type, boolean loadNewData){
        if(loadNewData == true || (MQQueueListResultCache == null || MQQueueListResultCache.QuerySuccess == false)){
            return MQPCF.GetPubList(queueManager,NameFilter, type);
        }
        else{
            return MQPubListResultCache;
        }
    }
    
    public static MQPubListResult GetPubList(MQQueueManager queueManager, String nameFilter, QueueType[] type ){
        MQPubListResult result = new MQPubListResult();
        PCFMessageAgent agent = null;
        try {
            agent = getPCFMessageAgent(queueManager);;
            PCFMessage pcfCmd = new PCFMessage(MQConstants.MQCMD_INQUIRE_TOPIC);        
            pcfCmd.addParameter(MQConstants.MQCA_TOPIC_NAME, nameFilter);
            PCFMessage[] pcfResponse = agent.send(pcfCmd);        
            WriteToDetailModel(pcfResponse, result, PubDetailModel.class, null); 
            result.QuerySuccess = true;
        } catch (MQDataException ex) {
            if(ex.getReason() == MQConstants.MQRCCF_CFH_COMMAND_ERROR ||ex.getReason() == MQConstants.MQRCCF_MQOPEN_FAILED ){
                result.QuerySuccess = true;
                disconnectAgent(agent);
                return result;
            }
            else{
                result.QuerySuccess = false;
                result.ErrorMessage = MQUtility.getMQReturnMessage(ex.getCompCode(), ex.getReason());
                LogWriter.WriteToLog("MQPCF", "GetPubList", ex);
            }
        } catch (IOException ex) {
            result.QuerySuccess = false;
            result.ErrorMessage = ex.getMessage();
            LogWriter.WriteToLog("MQPCF", "GetPubList", ex);
        } 
        MQPubListResultCache = result;
        disconnectAgent(agent);        
        return result;
    }
 
    public static MQSubListResult GetSubList(MQQueueManager queueManager, String NameFilter, QueueType[] type, boolean loadNewData){
        if(loadNewData == true || (MQQueueListResultCache == null || MQQueueListResultCache.QuerySuccess == false)){
            return MQPCF.GetSubList(queueManager,NameFilter, type);
        }
        else{
            return MQSubListResultCache;
        }
    }
    
    public static MQSubListResult GetSubList(MQQueueManager queueManager, String nameFilter, QueueType[] type ){
        MQSubListResult result = new MQSubListResult();
        PCFMessageAgent agent = null;
        try {
            agent = getPCFMessageAgent(queueManager);;
            PCFMessage pcfCmd = new PCFMessage(MQConstants.MQCMD_INQUIRE_SUBSCRIPTION);        
            pcfCmd.addParameter(MQConstants.MQCACF_SUB_NAME, nameFilter);
            PCFMessage[] pcfResponse = agent.send(pcfCmd);        
            WriteToDetailModel(pcfResponse, result, SubDetailModel.class, null); 
            result.QuerySuccess = true;
        } catch (MQDataException ex) {
            if(ex.getReason() == MQConstants.MQRCCF_CFH_COMMAND_ERROR || ex.getReason() == MQConstants.MQRCCF_MQOPEN_FAILED ){
                result.QuerySuccess = true;
                disconnectAgent(agent);
                return result;
            }
            else{
                result.QuerySuccess = false;
                result.ErrorMessage = MQUtility.getMQReturnMessage(ex.getCompCode(), ex.getReason());
                LogWriter.WriteToLog("MQPCF", "GetSubList", ex);
            }
        } catch (IOException ex) {
            result.QuerySuccess = false;
            result.ErrorMessage = ex.getMessage();
            LogWriter.WriteToLog("MQPCF", "GetSubList", ex);
        } 
        MQSubListResultCache = result;
        disconnectAgent(agent);        
        return result;
    }
    
    public static MQSubListResult GetInactiveSubList(MQQueueManager queueManager){
        MQSubListResult allSubs = MQPCF.GetSubList(queueManager, "*", null, true);
        MQSubListResult result = new MQSubListResult();
        ArrayList<SubDetailModel> resultSubDetailModel = new ArrayList<SubDetailModel>();
        if(allSubs.QuerySuccess){
            ArrayList<SubDetailModel> subs = allSubs.GetFilterDataModels("*", false);
            for(SubDetailModel sub : subs){
                MQQueueListResult destQueue = MQPCF.GetQueueList(queueManager, sub.Destination, new QueueType[]{QueueType.Local});
                ArrayList<MQQueueListResult.QueueDetailModel> finalDestQueue = destQueue.GetFilterDataModels(sub.Destination, false, false);
                if(finalDestQueue != null){
                    if(finalDestQueue.size() > 0 && finalDestQueue.get(0).OpenInputCount != null && finalDestQueue.get(0).OpenOutputCount != null && finalDestQueue.get(0).CurrentQueueDepth != null){
                        if((finalDestQueue.get(0).OpenInputCount == 0 && finalDestQueue.get(0).OpenOutputCount == 0) && finalDestQueue.get(0).CurrentQueueDepth > 0){      
                            resultSubDetailModel.add(sub);
                        }
                    }
                }
            }
            result.QuerySuccess = true;
            result.DataModels = resultSubDetailModel;
        } else{
            result.QuerySuccess = false;
            result.ErrorMessage = "There are no inactive subs at this time to be reported.";
        }
        return result;
    }
    
    //private
    private static <T, Y> void WriteToDetailModel(PCFMessage[] pcfResponse , T model, Class<Y> modelClass,  Object[] type){
        // The reflection lookups are done once per call, not once per returned object
        Constructor<Y> constructor;
        Field typeField;
        Field nameField;
        ArrayList<Y> modelArray;
        List<Field> valueFields;
        try {
            constructor = modelClass.getDeclaredConstructor(model.getClass());
            typeField = modelClass.getField("Type");
            nameField = modelClass.getField("Name");
            modelArray = (ArrayList<Y>)model.getClass().getField("DataModels").get(model);
            valueFields = getValueFields(modelClass);
        } catch (NoSuchFieldException | NoSuchMethodException | IllegalAccessException | SecurityException ex) {
            LogWriter.WriteToLog(ex.fillInStackTrace());
            return;
        }
        List<Object> types = type == null ? null : Arrays.asList(type);
        for(PCFMessage response : pcfResponse){     
            if(response.getCompCode() == 0){
                try {
                    Y detailModel = constructor.newInstance(model);
                    setModelValue(detailModel, response, valueFields);
                    if(types == null || types.contains(typeField.get(detailModel))){                       
                        if(nameField.get(detailModel) != null){
                            ((DetailModelCore)detailModel).setDisplayValues();
                            modelArray.add(detailModel);
                        }
                    }
                } catch (InstantiationException ex) {
                    LogWriter.WriteToLog(ex.fillInStackTrace());
                } catch (IllegalAccessException | SecurityException | InvocationTargetException ex) {
                    LogWriter.WriteToLog(ex.fillInStackTrace());
                }
            }
        }
    }
    
    private static final Map<Class<?>, List<Field>> valueFieldsCache = new ConcurrentHashMap<>();

    // Builds an INQUIRE command; attributes = true asks only for the attributes the list shows
    private interface ListCommand {
        PCFMessage Create(boolean attributes);
    }

    // The lists ask only for the attributes they show (about a third of all of them, so a third
    // of the data on the network); if the queue manager refuses the attribute list, everything is asked
    private static PCFMessage[] sendForList(PCFMessageAgent agent, ListCommand command) throws MQDataException, IOException{
        try {
            return agent.send(command.Create(true));
        } catch (PCFException ex) {
            LogWriter.WriteToLog("MQPCF", "sendForList", ex);
            return agent.send(command.Create(false));
        }
    }

    // MQ constants of the fields of a list model that come from the given query
    private static int[] listAttributes(Class<?> modelClass, QueryType queryType){
        return getValueFields(modelClass).stream()
                .map(field -> field.getAnnotation(MQObjectListtAnnotation.class))
                .filter(annotation -> annotation.QueryType() == queryType)
                .mapToInt(MQObjectListtAnnotation::MQConstant)
                .distinct()
                .toArray();
    }

    // Fields whose MQObjectListtAnnotation asks to read their value from the PCF response
    private static List<Field> getValueFields(Class<?> modelClass){
        return valueFieldsCache.computeIfAbsent(modelClass, c -> {
            List<Field> fields = new ArrayList<>();
            for(Field field : c.getFields()){
                if(field.getAnnotation(MQObjectListtAnnotation.class).GetValue() == true){
                    fields.add(field);
                }
            }
            return fields;
        });
    }

    private static void setModelValue(Object model, PCFMessage response, List<Field> valueFields){
        for(Field field : valueFields){
            MQObjectListtAnnotation annotation = field.getAnnotation(MQObjectListtAnnotation.class);
            SetFieldValue(field, response, model, annotation.MQConstant(), annotation.VarType());
        }        
    }
    
    private static void SetFieldValue(Field field, PCFMessage response, Object model, int mqConstant, VariableType varType){
        try{
           switch(varType){
               case QueueType :{
                   QueueType value = ConstantConverter.ConvertConstantToQueueType((Integer) response.getParameterValue(mqConstant));
                   field.set(model, value);
                   break;
               }
               case ChannelType :{
                   ChannelType value = ConstantConverter.ConvertConstantToChannelType((Integer) response.getParameterValue(mqConstant));
                   field.set(model, value);
                   break;
               }
               case ChannelStatusType :{
                   ChannelStatusType value = ConstantConverter.ConvertConstantToChannelStatusType((Integer) response.getParameterValue(mqConstant));
                   field.set(model, value);
                   break;
               }
               case ChannelAuthType :{
                   ChannelAuthType value = ConstantConverter.ConvertConstantToChannelAuthType((Integer) response.getParameterValue(mqConstant));
                   field.set(model, value);
                   break;
               }
               case QueueDefinitionType :{
                   Object value = response.getParameterValue(mqConstant);
                   if(value != null){
                       int intValue = Integer.parseInt(value.toString());
                       String stringValue = null;
                       switch(intValue){
                           case MQConstants.MQQDT_PREDEFINED:
                               stringValue = "Predefined permanent queue";
                               break;
                           case MQConstants.MQQDT_PERMANENT_DYNAMIC:
                               stringValue = "Dynamically defined permanent queue";
                               break;
                           case MQConstants.MQQDT_SHARED_DYNAMIC:
                               stringValue = "Dynamically defined shared queue";
                               break;
                           case MQConstants.MQQDT_TEMPORARY_DYNAMIC:
                               stringValue = "Dynamically defined temporary queue";
                               break;
                       }
                       field.set(model, stringValue);
                   }
                   else{
                       field.set(model, null);
                   }
                   break;
               }
               case Number :{
                   Object value = response.getParameterValue(mqConstant);
                   if(value != null)
                        field.set(model, value);
                   else
                       field.set(model, null);
                   break;
               }
               case Text :{
                   Object value = response.getParameterValue(mqConstant);
                   if(value != null){
                       field.set(model, value.toString().trim());
                   }
                   else
                       field.set(model, null);
                   break;
               }
               case TextArray :{
                   Object value = response.getParameterValue(mqConstant);
                   if(value != null){
                       field.set(model, value);
                   }
                   else
                       field.set(model, null);
                   break;
               }
               case TrueFalse :{
                   String[] displayValues = field.getAnnotation(MQObjectListtAnnotation.class).TrueFalseDisplayValue();
                   Object value = response.getParameterValue(mqConstant);
                   if(value != null){
                        int objectValue = Integer.parseInt(value.toString());
                        field.set(model, objectValue == 1 ? displayValues[1] : displayValues[0]);
                   }
                   else{
                       field.set(model, null);
                   }
                   break;
               }
           }
       }catch (Exception ex){

       }
   }       
    
    private static ArrayList<ToolChannelStatusModel> ConveryToChModel(PCFMessage[] pcfResponse){
        ArrayList<ToolChannelStatusModel> models = new ArrayList<ToolChannelStatusModel>();
        for(PCFMessage message : pcfResponse){
            try {
                ToolChannelStatusModel model = new ToolChannelStatusModel();
                model.Value = new ChannelStatusValueModel();
                //model.Application = (String) message.getStringParameterValue(MQConstants.MQCACH_REMOTE_APPL_TAG);
                model.ChannelName = (String) message.getStringParameterValue(MQConstants.MQCACH_CHANNEL_NAME).trim();
                model.IpAddress = (String) message.getStringParameterValue(MQConstants.MQCACH_CONNECTION_NAME);
                model.Value.MSGS =  ((Integer) (message.getParameterValue(MQConstants.MQIACH_MSGS)));
                int a = (Integer) message.getParameterValue(MQConstants.MQIACH_CHANNEL_STATUS);
                model.Value.Status = ConstantConverter.ConvertConstantToChannelStatusType((Integer) message.getParameterValue(MQConstants.MQIACH_CHANNEL_STATUS));
                models.add(model);
            } catch (PCFException ex) {
                Logger.getLogger(MQPCF.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        return models;
    }
    
    private static void WriteToHashtable(ArrayList<ChannelStatusModel> chResult, Hashtable<String, ChannelStatusValueModel> table){
        for(ChannelStatusModel m : chResult){
            if(m.Name != null){
                String key = m.Name.trim();
                ChannelStatusValueModel value = new ChannelStatusValueModel();
                value.Status = m.Status;
                value.MSGS = m.MSGS;
                if(table.containsKey(key)){
                    value.MSGS = (table.get(key)).MSGS + value.MSGS;
                    table.put(key, value);
                }
                else{
                   table.put(key, value); 
                }
            }
        }
    }
    
    private static void GetCombinedChannelDetailResult(MQChannelListResult result, Hashtable<String, ChannelStatusValueModel> statusTable){
        for(ChannelDetailModel channelDetail : result.DataModels){
            if(channelDetail.Name != null){
                if(statusTable.containsKey(channelDetail.Name)){
                    channelDetail.Status = statusTable.get(channelDetail.Name).Status;
                }
                else{
                    channelDetail.Status = ChannelStatusType.Inactive;
                }
            }
        }
    }
    
    // Agents are pooled per queue manager: hand it back instead of disconnecting it
    private static void disconnectAgent(PCFMessageAgent agent){
        PCFAgentPool.Release(agent);
    }
    
    private static void readToPropertyModel(PCFMessage message, Object model ){
        for(Field field : model.getClass().getFields()){
            int mqConstant = field.getAnnotation(MQObjectPropertyAnnotation.class).MQConstant();
            VariableType varType = field.getAnnotation(MQObjectPropertyAnnotation.class).VarType();
            SetFieldValue(field, message, model, mqConstant,varType);
        }        
    }    

    private static MQCommandResult CreateOrModifyQueueProperties(MQQueueManager queueManager, MQQueuePropertyModel model, boolean isCreate){
        PCFMessageAgent agent = null;
        MQCommandResult result = new MQCommandResult();
        try {           
            agent = getPCFMessageAgent(queueManager);;
            PCFMessage pcfCmd = new PCFMessage(isCreate ? MQConstants.MQCMD_CREATE_Q : MQConstants.MQCMD_CHANGE_Q);
            addParameters(model, pcfCmd);
            if(!isCreate && model.Type != MQConstants.MQQT_MODEL){
                pcfCmd.addParameter(MQConstants.MQIACF_FORCE, MQConstants.MQFC_YES); //always force change
            }
            PCFMessage[] pcfResponse = agent.send(pcfCmd);
            assignCommandReturnResult(pcfResponse[0], result);
        } catch (MQDataException ex) {
            LogWriter.WriteToLog("MQPCF", "CreateOrModifyQueueProperties", ex);
            result.QuerySuccess = false;
            result.ErrorMessage = getMQReturnMessage(ex.getCompCode(), ex.getReason());
        } catch (IOException ex) {
            LogWriter.WriteToLog("MQPCF", "CreateOrModifyQueueProperties", ex);
            result.QuerySuccess = false;
            result.ErrorMessage = ex.getMessage();
        }
        disconnectAgent(agent);
        return result;
    }
    
    private static MQCommandResult CreateOrModifyChannelProperties(MQQueueManager queueManager, MQChannelPropertyModel model, boolean isCreate){
        PCFMessageAgent agent = null;
        MQCommandResult result = new MQCommandResult();
        try {           
            agent = getPCFMessageAgent(queueManager);;
            PCFMessage pcfCmd = new PCFMessage(isCreate ? MQConstants.MQCMD_CREATE_CHANNEL : MQConstants.MQCMD_CHANGE_CHANNEL);
            addParameters(model, pcfCmd);
            PCFMessage[] pcfResponse = agent.send(pcfCmd);
            assignCommandReturnResult(pcfResponse[0], result);
        } catch (MQDataException ex) {
            LogWriter.WriteToLog("MQPCF", "CreateOrModifyChannelProperties", ex);
            result.QuerySuccess = false;
            result.ErrorMessage = getMQReturnMessage(ex.getCompCode(), ex.getReason());
        } catch (IOException ex) {
            LogWriter.WriteToLog("MQPCF", "CreateOrModifyChannelProperties", ex);
            result.QuerySuccess = false;
            result.ErrorMessage = ex.getMessage();
        }
        disconnectAgent(agent);
        return result;
    }
    
    private static void addParameters(Object model, PCFMessage pcfCmd ){
        for(Field field : model.getClass().getFields()){
            try {
                int parameter = field.getAnnotation(MQObjectPropertyAnnotation.class).MQConstant();
                VariableType variableType = field.getAnnotation(MQObjectPropertyAnnotation.class).VarType();
                boolean sendToPCF = field.getAnnotation(MQObjectPropertyAnnotation.class).SendToPCF();
                Object value = field.get(model);
                if(value != null && sendToPCF){
                    switch(variableType){
                        case Text :
                            String stringValue = value.toString().trim();
                            if(!stringValue.isEmpty()){
                                pcfCmd.addParameter(parameter, stringValue);
                            }
                            break;
                        case TextArray :
                            String[] stringValues = (String[])value;
                            if(stringValues.length > 0){
                                pcfCmd.addParameter(parameter, stringValues);
                            }
                            break;
                        case Number: 
                            try{
                                Integer paraValue = Integer.parseInt(value.toString());
                                pcfCmd.addParameter(parameter, paraValue);
                            }catch (Exception ex){
                                
                            }
                            break;
                    }
                }
            } catch (IllegalArgumentException | IllegalAccessException ex) {
                LogWriter.WriteToLog("MQPCF", "addParameters", ex);
            }
        }        
    }
    
    private static void assignCommandReturnResult(PCFMessage pcfResponse,MQCommandResult result){
        if(pcfResponse.getCompCode() == 0){
            result.QuerySuccess = true;
            result.ReturnMessage = getMQReturnMessage(pcfResponse.getCompCode(), pcfResponse.getReason());
        }
        else{
            result.QuerySuccess = false;
            result.ErrorMessage = getMQReturnMessage(pcfResponse.getCompCode(), pcfResponse.getReason());                
        }    
    }
    
    private static String getMQReturnMessage(int compCode, int reason){
        String msg = MQConstants.lookupCompCode(compCode) + " : (" + MQConstants.lookupReasonCode(reason) +" )";
        msg = msg.replace("_", " ");
        msg =  msg.toLowerCase();
        msg = msg.replace("mqcc", "Command");
        msg = msg.replace("mqrccf", "");
        msg = msg.replace("mqrc", "");
        return msg;
    }
    
    /**
     * Sends any PCF command with a pooled agent. Error responses are returned, not thrown,
     * so the caller can check each response's completion code.
     */
    public static PCFMessage[] Send(MQQueueManager queueManager, PCFMessage command) throws MQDataException, IOException{
        PCFMessageAgent agent = getPCFMessageAgent(queueManager);
        try {
            agent.setCheckResponses(false);
            return agent.send(command);
        } finally {
            disconnectAgent(agent);
        }
    }

    private static PCFMessageAgent getPCFMessageAgent(MQQueueManager queueManager) throws MQDataException{
        return PCFAgentPool.Acquire(queueManager, MQPCF::createPCFMessageAgent);
    }

    private static PCFMessageAgent createPCFMessageAgent(MQQueueManager queueManager) throws MQDataException{
        PCFMessageAgent agent = new ReadAheadPCFMessageAgent();
        MQQueue defaultModelQueue = null;
        
        try{
            defaultModelQueue = queueManager.accessQueue("SYSTEM.DEFAULT.MODEL.QUEUE", CMQC.MQOO_INQUIRE);
        }
        catch(MQException ex){
            setTOMQExplorerReplyQueue(agent);
        }
        try {
            if(defaultModelQueue != null){
                if(!defaultModelQueue.isOpen() || defaultModelQueue.getInhibitGet() == 1 || defaultModelQueue.getInhibitPut()== 1 ){
                    setTOMQExplorerReplyQueue(agent);
                }
            }
        } catch (MQException ex) {
            LogWriter.WriteToLog("MQPCF", "getPCFMessageAgent", ex);
            setTOMQExplorerReplyQueue(agent);
        }
        if(defaultModelQueue != null){
            try {
                defaultModelQueue.close();
            } catch (MQException ex) {
                Logger.getLogger(MQPCF.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        agent.connect(queueManager);
        return agent;
    }
    
    private static void setTOMQExplorerReplyQueue(PCFMessageAgent agent){
        agent.setModelQueueName("SYSTEM.MQEXPLORER.REPLY.MODEL");
        agent.setReplyQueuePrefix("AMQ.MQEXPLORER.");  
    }
}
