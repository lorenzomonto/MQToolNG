package UI.Models;

import MQApi.Models.Query.ConnectionDetailModel;
import com.ibm.mq.MQQueueManager;

/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

/**
 *
 * @author jzhou
 */
public class TreeNodeObject {
    public String Name;
    public TreeNodeObjectType Type;
    public MQQueueManager QueueManager;
    public ConnectionDetailModel ConnectionDetail;
    // Queue manager nodes: state shown in the list
    public boolean Connecting;
    /** Short reason of the last failed connection, cleared when it connects or its settings change. */
    public String LastError;
    /** From the last overview loaded while connected; null before. */
    public QueueManagerSummary Summary;

    public TreeNodeObject(String name, TreeNodeObjectType type){
        this.Name = name;
        this.Type = type;
    }

    public boolean IsConnected(){
        return QueueManager != null && QueueManager.isConnected();
    }
    @Override
    public String toString() {
        if(Type == TreeNodeObjectType.QueueManager && ConnectionDetail != null && ConnectionDetail.IsRemote()){
            return this.Name +" (via "+ ConnectionDetail.ViaQueueManager.trim() + ")";
        }
        if(Type == TreeNodeObjectType.QueueManager && ConnectionDetail != null){
            return this.Name +" ("+ ConnectionDetail.Host + ":" +ConnectionDetail.Port +")";
        }
        else{
            return this.Name;
        }
    }
    public enum TreeNodeObjectType{
        Root,
        /** A section of the queue manager list: connected or not connected. */
        QueueManagerFolder,
        QueueManager,
        Queues,
        Channel,
        ChannelAuth,
        QueueManagerClusterFolder,
        Pub,
        Sub,
    }
}
