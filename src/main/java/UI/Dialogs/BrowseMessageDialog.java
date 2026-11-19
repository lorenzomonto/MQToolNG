/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package UI.Dialogs;

import UI.Helpers.SwingThread;
import MQApi.Enums.LogType;
import MQApi.Enums.QueueType;
import MQApi.Logs.LogWriter;
import MQApi.Models.MQMessageIdModel;
import MQApi.QueryModel.MQMessageListResult;
import MQApi.Utilities.MQUtility;
import UI.Helpers.DateTimeHelper;
import UI.Helpers.IconManager;
import UI.Helpers.TableHelper;
import UI.Misc.CustomTableCellRender;
import UI.Models.TableListObject;
import com.ibm.mq.MQException;
import com.ibm.mq.MQMessage;
import com.ibm.mq.MQQueueManager;
import java.awt.ComponentOrientation;
import java.awt.Image;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.ImageIcon;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;

/**
 *
 * @author jzhou
 */
public class BrowseMessageDialog extends DialogBase {
    
    private int maxMessagePerPage = 1000;
    boolean isAlias;
    private MQMessageIdModel next = null;
    private MQMessageIdModel prev = null;
    private int fromPosition = 1;
    private int totalPage;
    private int currentPage = 1;
    private HashMap<Integer, MQMessageIdModel> pageInfoMap = new HashMap<Integer, MQMessageIdModel>();
    private final javax.swing.JTextField pageField = new javax.swing.JTextField(4);
    private final javax.swing.JButton firstButton = new javax.swing.JButton();
    private final javax.swing.JButton lastButton = new javax.swing.JButton();

    public BrowseMessageDialog(java.awt.Frame parent, boolean modal, MQQueueManager queueManager, TableListObject selectedObject) {
        super(parent, modal,  queueManager,selectedObject );
        this.isAlias = ((QueueType)selectedObject.Type) == QueueType.Alias;
        initComponents();
        initCustomProperties();
        updateTable();
    }

    private void initCustomProperties(){
        this.setTitle("Message browser");
        // Room for the message columns (the generated layout packs the dialog small)
        this.setSize(Math.max(getWidth(), 1000), Math.max(getHeight(), 640));
        this.setIconImage(iconManager.BrowseMessageIcon().getImage());   
        String queueManagerName = "";
        try {
            queueManagerName = MQApi.Connection.MQConnection.QueueManagerName(queueManager);
        } catch (MQException ex) {
            Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, null, ex);
        }
        this.QueueManagerNameLabel.setText(queueManagerName);
        this.QueueNameLabel.setText(selectedObject.ObjectName);
        this.Refreshbutton.setEnabled(false);
        MessageListTable.setAutoCreateRowSorter(true);
        TableHelper.ToggleContentTable(MessageListTable, true);
        initPaging();
    }

    // First and last page buttons, and the page number that can be typed (Enter goes to it); the tool bar
    // is laid out from right to left: last, next, "/ total", page, previous, first
    private void initPaging(){
        firstButton.setIcon(iconManager.FirstIcon());
        firstButton.setToolTipText("First page");
        firstButton.setFocusable(false);
        firstButton.addActionListener(e -> goToPage(1));
        lastButton.setIcon(iconManager.LastIcon());
        lastButton.setToolTipText("Last page");
        lastButton.setFocusable(false);
        lastButton.addActionListener(e -> goToPage(totalPage));
        PreviousButton.setToolTipText("Previous page");
        NextButton.setToolTipText("Next page");
        pageField.setComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);
        pageField.setHorizontalAlignment(javax.swing.JTextField.CENTER);
        pageField.setToolTipText("Type a page number and press Enter to go to it");
        // The tool bar of the form is 25 px high: a compact field fits in it
        pageField.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE, "margin: 0,4,0,4; minimumHeight: 22");
        pageField.setMaximumSize(new java.awt.Dimension(56, 24));
        pageField.setPreferredSize(new java.awt.Dimension(56, 24));
        pageField.addActionListener(e -> {
            try{
                int page = Integer.parseInt(pageField.getText().trim());
                if(page >= 1 && page <= totalPage){
                    goToPage(page);
                    return;
                }
            }
            catch(NumberFormatException ex){
                // Not a number: the current page is shown again
            }
            java.awt.Toolkit.getDefaultToolkit().beep();
            pageField.setText(Integer.toString(currentPage));
            pageField.selectAll();
        });
        PageInfoLabel.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 0, 4));
        PagingToolBar.add(lastButton, 0);
        PagingToolBar.add(javax.swing.Box.createHorizontalStrut(4), 3);
        PagingToolBar.add(pageField, 4);
        PagingToolBar.add(javax.swing.Box.createHorizontalStrut(4), 5);
        PagingToolBar.add(firstButton);
    }

    private void goToPage(int page){
        if(page == currentPage){
            pageField.setText(Integer.toString(currentPage));
            return;
        }
        currentPage = page;
        fromPosition = (currentPage - 1) * maxMessagePerPage + 1;
        updateTable();
    }

    private JPopupMenu getMessagelListTablePopupMenu(){
        JPopupMenu popup = new JPopupMenu();
        JMenuItem backupMenuItem = new JMenuItem("Backup messages to file",iconManager.BackupMessageIcon());
        JMenuItem saveContentMenuItem = new JMenuItem("Save message content to file",iconManager.BackupMessageIcon());
        JMenuItem deleteMenuItem = new JMenuItem("Delete messages",iconManager.Delete());
        deleteMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                deleteMessages();
            }
        });
        backupMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                backupMessages(false);
            }
        });
        saveContentMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                backupMessages(true);
            }
        });
        popup.add(saveContentMenuItem);
        popup.add(backupMenuItem);
        popup.add(deleteMenuItem);
        
        return popup;       
    }
    
    private void deleteMessages(){
         ArrayList<TableListObject> selectedObjects = TableHelper.GetCurrentTableSelectRowObjects(MessageListTable);
         if(selectedObjects != null && selectedObjects.size() > 0){
            ArrayList<MQMessageIdModel> idModels = turnToMessageIdModel(selectedObjects);
            DestoryMessagesDialog dialog = new DestoryMessagesDialog(ParentJFrame, true ,queueManager, selectedObject, idModels,false);
            dialog.AddDialogActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    updateTable();
                    FireActionEvent();
                }
            });
            dialog.setLocationRelativeTo(this);
            dialog.setVisible(true);  
         }
    }
    
    private void backupMessages(boolean isSaveMessageContent){
        ArrayList<TableListObject> selectedObjects = TableHelper.GetCurrentTableSelectRowObjects(MessageListTable);
        if(selectedObjects != null && selectedObjects.size() > 0){
            ArrayList<MQMessageIdModel> idModels = turnToMessageIdModel(selectedObjects);
            BackupRestoreMessageDialog dialog = DialogFactory.CreateBackupRestoreMessageDialog(ParentJFrame, true,queueManager, selectedObject, idModels);
            dialog.SetUsage(isSaveMessageContent ? BackupRestoreMessageDialog.Usage_SaveMsgContent : BackupRestoreMessageDialog.Usage_Backup);
            dialog.AddDialogActionListener(new ActionListener() {

                @Override
                public void actionPerformed(ActionEvent e) {
                    updateTable();
                    FireActionEvent();
                }
            });
            dialog.setLocationRelativeTo(this);
            dialog.setVisible(true); 
        }
    }
    
    private ArrayList<MQMessageIdModel> turnToMessageIdModel(ArrayList<TableListObject> selectedObjects){
        ArrayList<MQMessageIdModel> models = new ArrayList<MQMessageIdModel>();
        for(TableListObject object : selectedObjects){
            MQMessageIdModel idModel = object.MessageIdInfo;
            models.add(idModel);
        }
        return models;
    }
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        jLabel1 = new javax.swing.JLabel();
        jLabel2 = new javax.swing.JLabel();
        QueueManagerNameLabel = new javax.swing.JLabel();
        QueueNameLabel = new javax.swing.JLabel();
        Refreshbutton = new javax.swing.JButton();
        Closebutton = new javax.swing.JButton();
        UpdateTimeLabel = new javax.swing.JLabel();
        PagingToolBar = new javax.swing.JToolBar();
        NextButton = new javax.swing.JButton();
        PageInfoLabel = new javax.swing.JLabel();
        PreviousButton = new javax.swing.JButton();
        jPanel1 = new javax.swing.JPanel();
        TablePannel = new javax.swing.JScrollPane();
        MessageListTable = new javax.swing.JTable(){
            public Class getColumnClass(int column)
            {
                if(getValueAt(0, column) != null)
                return getValueAt(0, column).getClass();
                else
                return Object.class;
            }
        };
        ProgressBarPanel = new javax.swing.JPanel();
        ProgressBar = new javax.swing.JProgressBar();

        setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        setMinimumSize(new java.awt.Dimension(700, 500));
        setModal(true);

        jLabel1.setText("Queue manager name:");
        jLabel1.setPreferredSize(new java.awt.Dimension(150, 25));

        jLabel2.setText("Queue name:");
        jLabel2.setPreferredSize(new java.awt.Dimension(150, 25));

        QueueManagerNameLabel.setPreferredSize(new java.awt.Dimension(400, 25));

        QueueNameLabel.setPreferredSize(new java.awt.Dimension(400, 25));

        Refreshbutton.setText("Refresh");
        Refreshbutton.setPreferredSize(new java.awt.Dimension(100, 25));
        Refreshbutton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                RefreshbuttonActionPerformed(evt);
            }
        });

        Closebutton.setText("Close");
        Closebutton.setPreferredSize(new java.awt.Dimension(100, 25));
        Closebutton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                ClosebuttonActionPerformed(evt);
            }
        });

        UpdateTimeLabel.setPreferredSize(new java.awt.Dimension(250, 25));

        PagingToolBar.setFloatable(false);
        PagingToolBar.setRollover(true);
        PagingToolBar.setComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);

        NextButton.setIcon(iconManager.RightIcon());
        NextButton.setFocusable(false);
        NextButton.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
        NextButton.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        NextButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                NextButtonActionPerformed(evt);
            }
        });
        PagingToolBar.add(NextButton);
        PagingToolBar.add(PageInfoLabel);

        PreviousButton.setIcon(iconManager.LeftIcon());
        PreviousButton.setFocusable(false);
        PreviousButton.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
        PreviousButton.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        PreviousButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                PreviousButtonActionPerformed(evt);
            }
        });
        PagingToolBar.add(PreviousButton);

        jPanel1.setLayout(new java.awt.CardLayout());

        MessageListTable.setModel(new javax.swing.table.DefaultTableModel(

        ));
        MessageListTable.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                MessageListTableMouseClicked(evt);
            }
        });
        TablePannel.setViewportView(MessageListTable);

        jPanel1.add(TablePannel, "card2");

        ProgressBar.setIndeterminate(true);
        ProgressBar.setPreferredSize(new java.awt.Dimension(150, 25));

        javax.swing.GroupLayout ProgressBarPanelLayout = new javax.swing.GroupLayout(ProgressBarPanel);
        ProgressBarPanel.setLayout(ProgressBarPanelLayout);
        ProgressBarPanelLayout.setHorizontalGroup(
            ProgressBarPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(ProgressBarPanelLayout.createSequentialGroup()
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addComponent(ProgressBar, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );
        ProgressBarPanelLayout.setVerticalGroup(
            ProgressBarPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(ProgressBarPanelLayout.createSequentialGroup()
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addComponent(ProgressBar, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );

        jPanel1.add(ProgressBarPanel, "card3");

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                    .addComponent(jPanel1, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(PagingToolBar, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(UpdateTimeLabel, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addComponent(Refreshbutton, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(18, 18, 18)
                        .addComponent(Closebutton, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addGroup(javax.swing.GroupLayout.Alignment.LEADING, layout.createSequentialGroup()
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addGroup(layout.createSequentialGroup()
                                .addComponent(jLabel1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                                .addComponent(QueueManagerNameLabel, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                            .addGroup(layout.createSequentialGroup()
                                .addComponent(jLabel2, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                                .addComponent(QueueNameLabel, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)))
                        .addGap(0, 0, Short.MAX_VALUE)))
                .addContainerGap())
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(QueueManagerNameLabel, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel2, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(QueueNameLabel, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(PagingToolBar, javax.swing.GroupLayout.PREFERRED_SIZE, 25, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jPanel1, javax.swing.GroupLayout.DEFAULT_SIZE, 336, Short.MAX_VALUE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(Refreshbutton, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(Closebutton, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(UpdateTimeLabel, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addContainerGap())
        );

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void ClosebuttonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_ClosebuttonActionPerformed
        Close();
    }//GEN-LAST:event_ClosebuttonActionPerformed

    private void RefreshbuttonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_RefreshbuttonActionPerformed
         updateTable();
    }//GEN-LAST:event_RefreshbuttonActionPerformed

    private void PreviousButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_PreviousButtonActionPerformed
        pageInfoMap.remove(currentPage);
        this.currentPage -=1;
        fromPosition = (this.currentPage - 1) * maxMessagePerPage + 1;
        updateTable();  
    }//GEN-LAST:event_PreviousButtonActionPerformed

    private void NextButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_NextButtonActionPerformed
        this.currentPage +=1;
        fromPosition = (this.currentPage - 1) * maxMessagePerPage  + 1;
        updateTable();                
    }//GEN-LAST:event_NextButtonActionPerformed

    private void MessageListTableMouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_MessageListTableMouseClicked
        if(evt.getButton() == MouseEvent.BUTTON1 && evt.getClickCount() == 2){
            showMessageEditDialog();
        }
        else if(evt.getButton() == MouseEvent.BUTTON3){
            TableHelper.SetSelectedRow(MessageListTable, evt.getPoint());
            ArrayList<TableListObject> selectedObjects = TableHelper.GetCurrentTableSelectRowObjects(MessageListTable);
            if(selectedObjects != null && selectedObjects.size() > 0){
                JPopupMenu popup = getMessagelListTablePopupMenu();
                popup.show(evt.getComponent(), evt.getPoint().x, evt.getPoint().y);
            }
        }
    }//GEN-LAST:event_MessageListTableMouseClicked
    
    private void updateTable(){
        tableLoadStart();      
        Thread thread = new Thread(new Runnable() {

            @Override
            public void run() {
                int queueDepth = MQUtility.GetQueueDepth(queueManager, selectedObject.ObjectName);
                totalPage = getTotalPage(queueDepth, maxMessagePerPage);
                if(currentPage > totalPage){
                    currentPage = totalPage;
                    fromPosition = (currentPage - 1) * maxMessagePerPage  + 1;
                }
                MQMessageIdModel from = pageInfoMap.get(currentPage);
                MQMessageListResult result = MQUtility.GetMessageList(queueManager, selectedObject.ObjectName, maxMessagePerPage, isAlias, from,fromPosition);
                if(result.QuerySuccess){
                    TableHelper.UpdateContentTable(MessageListTable, result.DataModels);                       
                }
                // Swing components are updated on the event thread
                SwingThread.RunOnEdt(() -> {
                    if(!result.QuerySuccess){
                        JOptionPane.showMessageDialog(ParentJFrame, result.ErrorMessage, "Error", JOptionPane.ERROR_MESSAGE);
                    }
                    tableLoadFinish();
                });
            }
        });
        thread.start();       
    }
    
    private int getTotalPage(int queueDepth, int maxMessagePerPage){
        int result = queueDepth % maxMessagePerPage > 0 ? 1 : 0;
        result += queueDepth / maxMessagePerPage;
        return result;
    }
    
    private void tableLoadStart(){
        this.PagingToolBar.setEnabled(false);
        this.NextButton.setEnabled(false);
        this.PreviousButton.setEnabled(false);
        this.firstButton.setEnabled(false);
        this.lastButton.setEnabled(false);
        this.pageField.setEnabled(false);
        this.Refreshbutton.setEnabled(false);
        this.TablePannel.setVisible(false);
        this.MessageListTable.setEnabled(false);
        ProgressBar.setIndeterminate(true);
        this.ProgressBarPanel.setVisible(true);
    }
    
    private void tableLoadFinish(){
        String timeStamp = DateTimeHelper.GetCurrentTimeStamp();
        this.ProgressBarPanel.setVisible(false);
        this.TablePannel.setVisible(true);
        this.MessageListTable.setEnabled(true);
        this.UpdateTimeLabel.setText("Last updated: " + timeStamp + "");
        this.Refreshbutton.setEnabled(true);     
        this.PageInfoLabel.setText("/ " + Math.max(this.totalPage, 1));
        this.pageField.setText(Integer.toString(Math.max(this.currentPage, 1)));
        ProgressBar.setIndeterminate(false);
        if(totalPage > 1){
            this.PreviousButton.setEnabled(currentPage > 1);
            this.firstButton.setEnabled(currentPage > 1);
            this.NextButton.setEnabled(currentPage < totalPage);
            this.lastButton.setEnabled(currentPage < totalPage);
            this.pageField.setEnabled(true);
            this.PagingToolBar.setEnabled(true);
            TableListObject obj  = TableHelper.GetLastTableRowObject(MessageListTable);
            next = obj.MessageIdInfo;
            pageInfoMap.put(currentPage + 1, next);
        }
        else{
            this.NextButton.setEnabled(false);
            this.PreviousButton.setEnabled(false);
            this.PagingToolBar.setEnabled(false);
        }
        
    }
    
    private void showMessageEditDialog(){
        
        TableListObject selectedRowObject = TableHelper.GetCurrentTableSelectRowObject(MessageListTable);
        MQMessageIdModel messageId = selectedRowObject.MessageIdInfo;
        MessageEditDialog dialog = DialogFactory.CreateMessageEditDialog(ParentJFrame, true, queueManager, selectedObject.ObjectName,messageId);
        dialog.AddDialogActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                updateTable();
                FireActionEvent();
            }
        });
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }



    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton Closebutton;
    private javax.swing.JTable MessageListTable;
    private javax.swing.JButton NextButton;
    private javax.swing.JLabel PageInfoLabel;
    private javax.swing.JToolBar PagingToolBar;
    private javax.swing.JButton PreviousButton;
    private javax.swing.JProgressBar ProgressBar;
    private javax.swing.JPanel ProgressBarPanel;
    private javax.swing.JLabel QueueManagerNameLabel;
    private javax.swing.JLabel QueueNameLabel;
    private javax.swing.JButton Refreshbutton;
    private javax.swing.JScrollPane TablePannel;
    private javax.swing.JLabel UpdateTimeLabel;
    private javax.swing.JLabel jLabel1;
    private javax.swing.JLabel jLabel2;
    private javax.swing.JPanel jPanel1;
    // End of variables declaration//GEN-END:variables
}
