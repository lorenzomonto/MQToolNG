/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package MQApi.QueryModel;

import MQApi.Utilities.NameFilter;
import MQApi.Enums.ChannelAuthType;
import MQApi.Enums.QueryType;
import MQApi.Enums.QueueType;
import MQApi.Enums.VariableType;
import MQApi.Result.Annotations.MQObjectListtAnnotation;
import com.ibm.mq.constants.MQConstants;
import java.util.ArrayList;

/**
 *
 * @author jzhou
 */
public class MQSubListResult extends MQQueryResultBase  {
    
    public ArrayList<SubDetailModel> DataModels = new ArrayList<SubDetailModel>(); 
    
    public ArrayList<SubDetailModel> GetFilterDataModels(String searchString, boolean showSystem){
        if(DataModels != null && DataModels.size() > 0){
            searchString = searchString.trim();
            if(searchString == null || searchString.isEmpty()){
                searchString = "*";
            }
            final NameFilter nameFilter = NameFilter.Compile(searchString);
            ArrayList<SubDetailModel> resultDataModels = (ArrayList<SubDetailModel> )DataModels.clone();
            ArrayList<SubDetailModel> toBeRemoved = new ArrayList<SubDetailModel>();

            if(!showSystem){
                for(SubDetailModel t : resultDataModels){
                    if( t.Name != null && t.Name.startsWith("SYSTEM")){
                        toBeRemoved.add(t);
                    }
                }
            }

            if(!nameFilter.IsEmpty()){
                for(SubDetailModel t : resultDataModels){
                    if(!nameFilter.Matches(t.Name)){
                        toBeRemoved.add(t);
                    }
                }
            }
            for(SubDetailModel r : toBeRemoved){
                resultDataModels.remove(r);
            }
            return resultDataModels;
        }
        return null;
    }
    
    public class SubDetailModel extends DetailModelCore {
        @MQObjectListtAnnotation(DisplayName = "Subscription name", MQConstant = MQConstants.MQCACF_SUB_NAME, VarType = VariableType.Text, QueryType = QueryType.SubDetail)
        public String Name;
        @MQObjectListtAnnotation(DisplayName = "Subscription type", MQConstant = MQConstants.MQIACF_SUB_TYPE, VarType = VariableType.Number, QueryType = QueryType.SubDetail, ShowOnTable = false, ShowOnCSV = false)
        public Integer SubscriptionTypeValue;       
        @MQObjectListtAnnotation(DisplayName = "Subscription type", MQConstant = MQConstants.MQIACF_SUB_TYPE, VarType = VariableType.Text, QueryType = QueryType.SubDetail, GetValue = false)
        public String SubscriptionType; 
        @MQObjectListtAnnotation(DisplayName = "Topic object", MQConstant = MQConstants.MQCA_TOPIC_NAME, VarType = VariableType.Text, QueryType = QueryType.SubDetail)
        public String TopicObject;
        @MQObjectListtAnnotation(DisplayName = "Topic string", MQConstant = MQConstants.MQCA_TOPIC_STRING, VarType = VariableType.Text, QueryType = QueryType.SubDetail)
        public String TopicString;    
        @MQObjectListtAnnotation(DisplayName = "Destination", MQConstant = MQConstants.MQCACF_DESTINATION, VarType = VariableType.Text, QueryType = QueryType.SubDetail)
        public String Destination;  
        @MQObjectListtAnnotation(DisplayName = "Destination QMGR", MQConstant = MQConstants.MQCACF_DESTINATION_Q_MGR, VarType = VariableType.Text, QueryType = QueryType.SubDetail)
        public String DestinationQueueManager;  
        @MQObjectListtAnnotation(DisplayName = "Subscription user", MQConstant = MQConstants.MQCACF_SUB_USER_ID, VarType = VariableType.Text, QueryType = QueryType.SubDetail)
        public String SubscriptionUser;      
        @MQObjectListtAnnotation(DisplayName = "Subscription level", MQConstant = MQConstants.MQIACF_SUB_LEVEL, VarType = VariableType.Number, QueryType = QueryType.SubDetail)
        public Integer SubscriptionLevel; 
        @MQObjectListtAnnotation(DisplayName = "Selector", MQConstant = MQConstants.MQCACF_SUB_SELECTOR, VarType = VariableType.Text, QueryType = QueryType.SubDetail)
        public String Selector; 
        @MQObjectListtAnnotation(DisplayName = "Selector type", MQConstant = MQConstants.MQIACF_SELECTOR_TYPE, VarType = VariableType.Number, QueryType = QueryType.SubDetail, ShowOnTable = false, ShowOnCSV = false)
        public Integer SelectorTypeValue;       
        @MQObjectListtAnnotation(DisplayName = "Selector type", MQConstant = MQConstants.MQIACF_SELECTOR_TYPE, VarType = VariableType.Text, QueryType = QueryType.SubDetail, GetValue = false)
        public String SelectorType; 
        @MQObjectListtAnnotation(DisplayName = "Expiry", MQConstant = MQConstants.MQIACF_EXPIRY, VarType = VariableType.Number, QueryType = QueryType.SubDetail, ShowOnTable = false, ShowOnCSV = false)
        public Integer ExpiryValue;  
        @MQObjectListtAnnotation(DisplayName = "Expiry", MQConstant = MQConstants.MQIACF_EXPIRY, VarType = VariableType.Text, QueryType = QueryType.SubDetail, GetValue = false)
        public String Expiry; 
        @MQObjectListtAnnotation(DisplayName = "Creation date", MQConstant = MQConstants.MQCA_CREATION_DATE, VarType = VariableType.Text, QueryType = QueryType.SubDetail)
        public String CreationDate;   
        @MQObjectListtAnnotation(DisplayName = "Creation time", MQConstant = MQConstants.MQCA_CREATION_TIME, VarType = VariableType.Text, QueryType = QueryType.SubDetail)
        public String CreationTime;
        @MQObjectListtAnnotation(DisplayName = "Alteration date", MQConstant = MQConstants.MQCA_ALTERATION_DATE, VarType = VariableType.Text, QueryType = QueryType.SubDetail)
        public String AlterationDate;   
        @MQObjectListtAnnotation(DisplayName = "Alteration time", MQConstant = MQConstants.MQCA_ALTERATION_TIME, VarType = VariableType.Text, QueryType = QueryType.SubDetail)
        public String AlterationTime;
        
        @Override
        public void setDisplayValues(){
            if(SubscriptionTypeValue == MQConstants.MQSUBTYPE_PROXY){
                SubscriptionType = "Proxy";
            }
            else if(SubscriptionTypeValue == MQConstants.MQSUBTYPE_ADMIN){
                SubscriptionType = "Admin";
            }
            else{
                SubscriptionType = "Api";
            }
            
            if(ExpiryValue < 0){
                Expiry = "Unlimited";
            }
            else{
                Expiry = "" + ExpiryValue;
            }
            
            if(SelectorTypeValue == MQConstants.MQSELTYPE_NONE){
                SelectorType = "None";
            }
            else if(SelectorTypeValue == MQConstants.MQSELTYPE_STANDARD){
                SelectorType = "Standard";
            }
            else{
                SelectorType = "Extended";
            }
                
        }
    }
    
}
