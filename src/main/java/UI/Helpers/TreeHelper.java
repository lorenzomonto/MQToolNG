/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package UI.Helpers;

import MQApi.PCF.PCFAgentPool;
import MQApi.Connection.SessionPasswords;
import MQApi.Connection.MQConnection;
import MQApi.Models.Query.ConnectionDetailModel;
import UI.Models.TreeNodeObject;
import com.ibm.mq.MQException;
import com.ibm.mq.MQQueueManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.DefaultTreeSelectionModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

/**
 * The queue manager list: a section of the connected queue managers and one of the others, in
 * alphabetical order. A connected queue manager administered through another connected one is shown
 * under it. The root is hidden.
 *
 * @author jzhou
 */
public class TreeHelper {

    private static JTree treeBuff;
    // Every queue manager node, wherever it is shown (or hidden by the filter)
    private static final List<DefaultMutableTreeNode> queueManagerNodes = new ArrayList<>();
    private static DefaultMutableTreeNode connectedSection;
    private static DefaultMutableTreeNode notConnectedSection;
    private static String filter = "";
    private static boolean arranging;
    private static DefaultMutableTreeNode hiddenSelection;
    // User objects of the open nodes, kept while the filter hides them
    private static final Set<Object> expanded = Collections.newSetFromMap(new IdentityHashMap<>());

    public static void InitTreeView(JTree TreeView){
        TreeView.setSelectionModel(new SectionSkippingSelectionModel(TreeView));
        TreeView.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        TreeView.setRootVisible(false);
        TreeView.setShowsRootHandles(false);
        DefaultTreeModel model = (DefaultTreeModel)TreeView.getModel();
        DefaultMutableTreeNode root = (DefaultMutableTreeNode)model.getRoot();
        root.removeAllChildren();
        root.setUserObject(new TreeNodeObject(UI.AppInfo.NAME, TreeNodeObject.TreeNodeObjectType.Root));
        connectedSection = new DefaultMutableTreeNode(new TreeNodeObject("Connected", TreeNodeObject.TreeNodeObjectType.QueueManagerFolder));
        notConnectedSection = new DefaultMutableTreeNode(new TreeNodeObject("Not connected", TreeNodeObject.TreeNodeObjectType.QueueManagerFolder));
        queueManagerNodes.clear();
        expanded.clear();
        model.reload(root);
        treeBuff = TreeView;
    }

    public static void AddQueueManagerNodeToTreeView(JTree TreeView, ConnectionDetailModel connectionDetail){
        TreeNodeObject nodeObject = new TreeNodeObject(connectionDetail.QueueManager, TreeNodeObject.TreeNodeObjectType.QueueManager);
        nodeObject.ConnectionDetail = connectionDetail;
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(nodeObject);
        queueManagerNodes.add(node);
        Arrange(TreeView, null);
        if(node.getRoot() == TreeView.getModel().getRoot()){
            TreeView.setSelectionPath(new TreePath(node.getPath()));
        }
    }

    public static ConnectionDetailModel DeleteQueueManagerNodeFromTreeView(JTree TreeView){
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) TreeView.getLastSelectedPathComponent();
        TreeNodeObject nodeObject = (TreeNodeObject) node.getUserObject();

        if(nodeObject.IsConnected()){
            try {
                PCFAgentPool.ReleaseAll(nodeObject.QueueManager);
                nodeObject.QueueManager.disconnect();
                SessionPasswords.Forget(nodeObject.ConnectionDetail);
            } catch (MQException ex) {
                Logger.getLogger(TreeHelper.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        queueManagerNodes.remove(node);
        node.removeFromParent();
        if(hiddenSelection == node){
            hiddenSelection = null;
        }
        Arrange(TreeView, null);
        return nodeObject.ConnectionDetail;
    }

    public static void AddConnectedQueueManagerNode(JTree TreeView) throws MQException{
        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) TreeView.getLastSelectedPathComponent();
        TreeNodeObject nodeObject = (TreeNodeObject) selectedNode.getUserObject();
        ConnectionDetailModel connectionDetail = nodeObject.ConnectionDetail;
        MQQueueManager queueManager = MQConnection.GetMQQueueManager(connectionDetail);
        AddConnectedQueueManagerNode(TreeView, selectedNode, queueManager);
    }

    // Builds the child nodes of a queue manager node once its connection is open (must run on the EDT)
    public static void AddConnectedQueueManagerNode(JTree TreeView, DefaultMutableTreeNode selectedNode, MQQueueManager queueManager){
        TreeNodeObject nodeObject = (TreeNodeObject) selectedNode.getUserObject();
        if(queueManager != null){
            nodeObject.QueueManager = queueManager;
            nodeObject.LastError = null;
            TreeNodeObject queuesObject = new TreeNodeObject("Queues",TreeNodeObject.TreeNodeObjectType.Queues);
            TreeNodeObject channelObject = new TreeNodeObject("Channels",TreeNodeObject.TreeNodeObjectType.Channel);
            TreeNodeObject pubObject = new TreeNodeObject("Topics",TreeNodeObject.TreeNodeObjectType.Pub);
            TreeNodeObject subObject = new TreeNodeObject("Subscriptions",TreeNodeObject.TreeNodeObjectType.Sub);
            TreeNodeObject channelAuthObject = new TreeNodeObject("Channel auths",TreeNodeObject.TreeNodeObjectType.ChannelAuth);
            DefaultMutableTreeNode queueNodes = new DefaultMutableTreeNode(queuesObject);
            DefaultMutableTreeNode channelNodes = new DefaultMutableTreeNode(channelObject);
            DefaultMutableTreeNode pubNodes = new DefaultMutableTreeNode(pubObject);
            DefaultMutableTreeNode subNodes = new DefaultMutableTreeNode(subObject);
            DefaultMutableTreeNode channelAuthNodes = new DefaultMutableTreeNode(channelAuthObject);
            channelNodes.add(channelAuthNodes);
            // Before the queue managers administered through this one, if any
            selectedNode.insert(queueNodes, 0);
            selectedNode.insert(channelNodes, 1);
            selectedNode.insert(pubNodes, 2);
            selectedNode.insert(subNodes, 3);
            Arrange(TreeView, selectedNode);
            if(selectedNode.getRoot() == TreeView.getModel().getRoot()){
                TreeView.setSelectionPath(new TreePath(selectedNode.getPath()));
            }
        }
    }

    public static void DisconnectQueueManager(JTree TreeView) throws MQException{
        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) TreeView.getLastSelectedPathComponent();
        TreeNodeObject nodeObject = (TreeNodeObject) selectedNode.getUserObject();
        removeObjectFolders(selectedNode);
        try {
            if(nodeObject.IsConnected()){
                PCFAgentPool.ReleaseAll(nodeObject.QueueManager);
                nodeObject.QueueManager.disconnect();
                SessionPasswords.Forget(nodeObject.ConnectionDetail);
            }
        } finally {
            nodeObject.Summary = null;
            Arrange(TreeView, null);
            if(selectedNode.getRoot() == TreeView.getModel().getRoot()){
                TreeView.setSelectionPath(new TreePath(selectedNode.getPath()));
            }
        }
    }

    public static ConnectionDetailModel GetCurrentSelectedConnectionDetail(JTree TreeView){
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) TreeView.getLastSelectedPathComponent();
        if(node != null){
            TreeNodeObject nodeObject = (TreeNodeObject) node.getUserObject();
            return nodeObject.ConnectionDetail;
        }
        return null;
    }

    public static TreeNodeObject GetCurrentSelectedTreeNoteObject(JTree TreeView){
        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) TreeView.getLastSelectedPathComponent();
        if(selectedNode != null){
            TreeNodeObject nodeObject = (TreeNodeObject) selectedNode.getUserObject();
            return nodeObject;
        }
        return null;
    }

    public static MQQueueManager GetCurrentSelectedQueueManager(JTree TreeView){
        if(TreeView.getLastSelectedPathComponent() != null){
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) TreeView.getLastSelectedPathComponent();
            DefaultMutableTreeNode queueManagerNode = getQueueManagerNode(node);
            if(queueManagerNode != null){
                TreeNodeObject nodeObject = (TreeNodeObject) queueManagerNode.getUserObject();
                return nodeObject.QueueManager;
            }
        }
        return null;
    }

    public static ConnectionDetailModel GetCurrentConnectionDetail(JTree TreeView){
        if(TreeView == null){
            TreeView = treeBuff;
        }
        if(TreeView != null && TreeView.getLastSelectedPathComponent() != null){
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) TreeView.getLastSelectedPathComponent();
            DefaultMutableTreeNode queueManagerNode = getQueueManagerNode(node);
            if(queueManagerNode != null){
                TreeNodeObject nodeObject = (TreeNodeObject) queueManagerNode.getUserObject();
                return nodeObject.ConnectionDetail;
            }
        }
        return null;
    }

    public static boolean ShowSearchBarForCurrentNote(JTree TreeView){
        TreeNodeObject nodeObject = GetCurrentSelectedTreeNoteObject(TreeView);

        return nodeObject != null && nodeObject.Type != TreeNodeObject.TreeNodeObjectType.QueueManager && GetCurrentSelectedQueueManager(TreeView) != null;
    }

    public static void RefreshTreeView(JTree TreeView, ConnectionDetailModel connectionDetail){
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) TreeView.getLastSelectedPathComponent();
        TreeNodeObject nodeObject = (TreeNodeObject) node.getUserObject();
        nodeObject.Name = connectionDetail.QueueManager;
        nodeObject.ConnectionDetail = connectionDetail;
        nodeObject.LastError = null;
        // The name may have changed: the list puts it back in its alphabetical place
        Arrange(TreeView, null);
        if(node.getRoot() == TreeView.getModel().getRoot()){
            TreeView.setSelectionPath(new TreePath(node.getPath()));
        }
    }

    /** Shows only the queue managers whose name or address contains the text (ignoring case). */
    public static void SetFilter(JTree TreeView, String text){
        String value = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if(!value.equals(filter)){
            filter = value;
            Arrange(TreeView, null);
        }
    }

    /** True while the list is rebuilt: the selection events it causes are not user choices. */
    public static boolean IsArranging(){
        return arranging;
    }

    public static boolean IsSection(Object node){
        return node instanceof DefaultMutableTreeNode
                && ((DefaultMutableTreeNode) node).getUserObject() instanceof TreeNodeObject
                && ((TreeNodeObject) ((DefaultMutableTreeNode) node).getUserObject()).Type == TreeNodeObject.TreeNodeObjectType.QueueManagerFolder;
    }

    /** Number of queue managers shown in a section, those under another queue manager included. */
    public static int CountInSection(DefaultMutableTreeNode section){
        int count = 0;
        Enumeration<TreeNode> nodes = section.depthFirstEnumeration();
        while(nodes.hasMoreElements()){
            Object userObject = ((DefaultMutableTreeNode) nodes.nextElement()).getUserObject();
            if(userObject instanceof TreeNodeObject && ((TreeNodeObject) userObject).Type == TreeNodeObject.TreeNodeObjectType.QueueManager){
                count++;
            }
        }
        return count;
    }

    /** The connected queue manager nodes, the hidden ones included. */
    public static List<DefaultMutableTreeNode> ConnectedQueueManagerNodes(){
        List<DefaultMutableTreeNode> connected = new ArrayList<>();
        for(DefaultMutableTreeNode node : queueManagerNodes){
            if(((TreeNodeObject) node.getUserObject()).IsConnected()){
                connected.add(node);
            }
        }
        return connected;
    }

    /** True while a queue manager connection is opening. */
    public static boolean AnyConnecting(){
        for(DefaultMutableTreeNode node : queueManagerNodes){
            if(((TreeNodeObject) node.getUserObject()).Connecting){
                return true;
            }
        }
        return false;
    }

    /** The queue manager node of a node (itself, or the one its folder belongs to). */
    public static DefaultMutableTreeNode QueueManagerNodeOf(DefaultMutableTreeNode node){
        return node == null ? null : getQueueManagerNode(node);
    }

    /** Repaints the rows of a node and of its folders, after their state changed. */
    public static void RepaintNode(JTree TreeView, DefaultMutableTreeNode node){
        DefaultTreeModel model = (DefaultTreeModel) TreeView.getModel();
        if(node.getRoot() != model.getRoot()){
            return;
        }
        model.nodeChanged(node);
        for(int i = 0; i < node.getChildCount(); i++){
            model.nodeChanged(node.getChildAt(i));
        }
        TreeNode parent = node.getParent();
        while(parent != null && !IsSection(parent)){
            parent = parent.getParent();
        }
        if(parent != null){
            model.nodeChanged(parent); // the count of the section
        }
    }

    /**
     * Puts every queue manager in its section (or under the queue manager it is administered through),
     * keeping the selection and the expanded nodes.
     */
    public static void Arrange(JTree TreeView){
        Arrange(TreeView, null);
    }

    private static void Arrange(JTree TreeView, DefaultMutableTreeNode expand){
        DefaultTreeModel model = (DefaultTreeModel) TreeView.getModel();
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) model.getRoot();
        // Open nodes: as shown now, and as they were for the nodes the filter hides
        Enumeration<TreeNode> shownNodes = root.depthFirstEnumeration();
        while(shownNodes.hasMoreElements()){
            expanded.remove(((DefaultMutableTreeNode) shownNodes.nextElement()).getUserObject());
        }
        Enumeration<TreePath> expandedPaths = TreeView.getExpandedDescendants(new TreePath(root));
        while(expandedPaths != null && expandedPaths.hasMoreElements()){
            expanded.add(((DefaultMutableTreeNode) expandedPaths.nextElement().getLastPathComponent()).getUserObject());
        }
        if(expand != null){
            expanded.add(expand.getUserObject());
        }
        // Forget the nodes deleted or rebuilt since
        Set<Object> existing = Collections.newSetFromMap(new IdentityHashMap<>());
        for(DefaultMutableTreeNode node : queueManagerNodes){
            Enumeration<TreeNode> subtree = node.depthFirstEnumeration();
            while(subtree.hasMoreElements()){
                existing.add(((DefaultMutableTreeNode) subtree.nextElement()).getUserObject());
            }
        }
        expanded.retainAll(existing);
        DefaultMutableTreeNode selected = (DefaultMutableTreeNode) TreeView.getLastSelectedPathComponent();
        if(selected == null){
            selected = hiddenSelection;
        }

        arranging = true;
        try {
            for(DefaultMutableTreeNode node : queueManagerNodes){
                node.removeFromParent();
            }
            connectedSection.removeAllChildren();
            notConnectedSection.removeAllChildren();
            root.removeAllChildren();

            List<DefaultMutableTreeNode> shown = new ArrayList<>();
            for(DefaultMutableTreeNode node : queueManagerNodes){
                if(matchesFilter((TreeNodeObject) node.getUserObject())){
                    shown.add(node);
                }
            }
            shown.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(
                    String.valueOf(((TreeNodeObject) a.getUserObject()).Name), String.valueOf(((TreeNodeObject) b.getUserObject()).Name)));
            for(DefaultMutableTreeNode node : shown){
                TreeNodeObject nodeObject = (TreeNodeObject) node.getUserObject();
                if(!nodeObject.IsConnected()){
                    notConnectedSection.add(node);
                    continue;
                }
                DefaultMutableTreeNode via = connectedVia(shown, nodeObject);
                (via != null ? via : connectedSection).add(node);
            }
            if(connectedSection.getChildCount() > 0){
                root.add(connectedSection);
            }
            if(notConnectedSection.getChildCount() > 0){
                root.add(notConnectedSection);
            }
            model.reload(root);

            // Sections are always open; other nodes as they were (a node opens only if its parent is open)
            List<DefaultMutableTreeNode> pending = new ArrayList<>();
            for(int i = 0; i < root.getChildCount(); i++){
                DefaultMutableTreeNode section = (DefaultMutableTreeNode) root.getChildAt(i);
                TreeView.expandPath(new TreePath(section.getPath()));
                for(int j = 0; j < section.getChildCount(); j++){
                    pending.add((DefaultMutableTreeNode) section.getChildAt(j));
                }
            }
            while(!pending.isEmpty()){
                DefaultMutableTreeNode node = pending.remove(0);
                if(node.getChildCount() > 0 && expanded.contains(node.getUserObject())){
                    TreeView.expandPath(new TreePath(node.getPath()));
                    for(int j = 0; j < node.getChildCount(); j++){
                        pending.add((DefaultMutableTreeNode) node.getChildAt(j));
                    }
                }
            }
            if(selected != null && selected.getRoot() == root){
                TreeView.setSelectionPath(new TreePath(selected.getPath()));
                hiddenSelection = null;
            }
            else{
                hiddenSelection = selected; // selected again when the filter shows it
            }
        } finally {
            arranging = false;
        }
    }

    // The connected queue manager a remote one is administered through, when it is shown
    private static DefaultMutableTreeNode connectedVia(List<DefaultMutableTreeNode> shown, TreeNodeObject remote){
        if(remote.ConnectionDetail == null || !remote.ConnectionDetail.IsRemote()){
            return null;
        }
        for(DefaultMutableTreeNode node : shown){
            TreeNodeObject other = (TreeNodeObject) node.getUserObject();
            if(other != remote && other.IsConnected() && remote.ConnectionDetail.IsThrough(other.ConnectionDetail)){
                return node;
            }
        }
        return null;
    }

    private static boolean matchesFilter(TreeNodeObject nodeObject){
        if(filter.isEmpty()){
            return true;
        }
        String name = String.valueOf(nodeObject.Name).toLowerCase(Locale.ROOT);
        String address = nodeObject.ConnectionDetail == null ? "" : nodeObject.ConnectionDetail.Address().toLowerCase(Locale.ROOT);
        return name.contains(filter) || address.contains(filter);
    }

    // The object folders (queues, channels...) of a queue manager node, not the queue managers shown under it
    private static void removeObjectFolders(DefaultMutableTreeNode queueManagerNode){
        for(int i = queueManagerNode.getChildCount() - 1; i >= 0; i--){
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) queueManagerNode.getChildAt(i);
            if(((TreeNodeObject) child.getUserObject()).Type != TreeNodeObject.TreeNodeObjectType.QueueManager){
                queueManagerNode.remove(i);
            }
        }
    }

    private static DefaultMutableTreeNode getQueueManagerNode(DefaultMutableTreeNode node){
        if(((TreeNodeObject)node.getUserObject()).Type == TreeNodeObject.TreeNodeObjectType.QueueManager){
            return node;
        }
        else if((DefaultMutableTreeNode) node.getParent() == null){
            return null;
        }
        else{
            return getQueueManagerNode((DefaultMutableTreeNode) node.getParent());
        }
    }

    /**
     * Section rows cannot be selected: moving onto one with the keyboard goes on to the next row in
     * the same direction (clicks on them are ignored by the list).
     */
    private static class SectionSkippingSelectionModel extends DefaultTreeSelectionModel {
        private final JTree tree;

        SectionSkippingSelectionModel(JTree tree){
            this.tree = tree;
        }

        @Override
        public void setSelectionPaths(TreePath[] paths){
            if(paths != null && paths.length == 1 && paths[0] != null && IsSection(paths[0].getLastPathComponent())){
                int target = tree.getRowForPath(paths[0]);
                int step = target < getLeadSelectionRow() ? -1 : 1;
                int row = target + step;
                if(target < 0 || row < 0 || row >= tree.getRowCount()){
                    return;
                }
                paths = new TreePath[]{tree.getPathForRow(row)};
            }
            super.setSelectionPaths(paths);
        }

        @Override
        public void addSelectionPaths(TreePath[] paths){
            if(paths != null && paths.length == 1 && paths[0] != null && IsSection(paths[0].getLastPathComponent())){
                setSelectionPaths(paths);
                return;
            }
            super.addSelectionPaths(paths);
        }
    }
}
