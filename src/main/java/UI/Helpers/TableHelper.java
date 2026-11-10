/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package UI.Helpers;

import MQApi.Enums.TableContentType;
import MQApi.Models.MQMessageIdModel;
import MQApi.QueryModel.MQChannelAuthListResult.ChannelAuthDetailModel;
import MQApi.Result.Annotations.MQObjectListtAnnotation;
import MQApi.QueryModel.MQChannelListResult.ChannelDetailModel;
import MQApi.QueryModel.MQMessageListResult.MessageDetailModel;
import MQApi.QueryModel.MQPubListResult.PubDetailModel;
import MQApi.QueryModel.MQQueueListResult.QueueDetailModel;
import MQApi.QueryModel.MQSubListResult.SubDetailModel;
import UI.Misc.DepthCellRender;
import UI.Models.TableListObject;
import java.awt.Component;
import java.awt.Point;
import java.lang.reflect.Field;
import java.util.ArrayList;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.ListSelectionModel;
import javax.swing.event.TableModelEvent;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumnModel;

/**
 *
 * @author jzhou
 */
public class TableHelper {
    private static final int RESIZE_SAMPLE_ROWS = 200;

    /**
     * Read-only model whose column class is the class of the first non-null value.
     * The classes are computed once per data load: JTable asks for them on every cell paint.
     */
    private static class ContentTableModel extends DefaultTableModel {
        private Class<?>[] columnClasses;

        @Override
        public void fireTableChanged(TableModelEvent e) {
            columnClasses = null;
            super.fireTableChanged(e);
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            if(columnClasses == null || columnClasses.length != getColumnCount()){
                columnClasses = new Class<?>[getColumnCount()];
            }
            if(columnClasses[columnIndex] == null){
                Class<?> columnClass = Object.class;
                int rowCount = getRowCount();
                for(int i = 0 ; i < rowCount; i++){
                    Object value = getValueAt(i, columnIndex);
                    if(value != null){
                        columnClass = value.getClass();
                        break;
                    }
                }
                columnClasses[columnIndex] = columnClass;
            }
            return columnClasses[columnIndex];
        }

        @Override
        public boolean isCellEditable(int rowIndex, int mColIndex) {
          return false;
        }
    }
    
    public static void InitTable(JTable contentTable){
        SwingThread.RunOnEdt(() -> {
            ToggleContentTable(contentTable, false);
            contentTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            contentTable.setAutoCreateRowSorter(true);
            alignHeadersLeading(contentTable);
        });
    }

    // Headers aligned like the cell values instead of centered
    private static void alignHeadersLeading(JTable table){
        TableCellRenderer base = table.getTableHeader().getDefaultRenderer();
        if(base instanceof LeadingHeaderRenderer){
            return;
        }
        table.getTableHeader().setDefaultRenderer(new LeadingHeaderRenderer(base));
    }

    private static class LeadingHeaderRenderer implements TableCellRenderer {
        private final TableCellRenderer base;

        LeadingHeaderRenderer(TableCellRenderer base){
            this.base = base;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column){
            Component component = base.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if(component instanceof JLabel){
                ((JLabel) component).setHorizontalAlignment(SwingConstants.LEADING);
            }
            return component;
        }
    }

    // Can be called from a background task: the rows are built on the calling thread, the table is updated on the EDT
    public static<T> void UpdateContentTable(JTable contentTable, ArrayList<T> dataModel){
        ArrayList<String> headerNames = new ArrayList<String>();
        Object[][] tablleDataObjects = null;
        if(dataModel != null && dataModel.size() > 0){
            Class<?> modelClass = dataModel.get(0).getClass();
            ArrayList<Field> fields = new ArrayList<Field>();
            for(Field field : modelClass.getFields()){
                MQObjectListtAnnotation annotation = field.getAnnotation(MQObjectListtAnnotation.class);
                if(annotation != null && annotation.ShowOnTable()){
                    headerNames.add(annotation.DisplayName());
                    fields.add(field);
                }
            }
            tablleDataObjects = new Object[dataModel.size()][fields.size()];
            for(int i = 0; i < dataModel.size(); i++){
                T data = dataModel.get(i);
                TableContentType tableType = getTableContentType(data);
                for(int j = 0; j < fields.size(); j++){
                    try{                        
                        Object o = fields.get(j).get(data);
                        if(j == 0){
                            TableListObject obj = new TableListObject();
                            obj.ObjectName = o != null ? o.toString() : null;
                            obj.TableType = tableType;
                            if(tableType == TableContentType.MessageList){
                                MessageDetailModel message = (MessageDetailModel)data;
                                obj.MessageIdInfo = new MQMessageIdModel();
                                obj.MessageIdInfo.MessageId = message.MessageId;
                                obj.MessageIdInfo.CorrelationdId = message.CorrelationId;
                                obj.MessageIdInfo.position = Integer.parseInt(String.valueOf(message.Position));
                            }
                            setType(obj, data);
                            tablleDataObjects[i][j] = obj;
                        }
                        else{
                            tablleDataObjects[i][j] = o;
                        }
                    }catch(Exception ex){
                        tablleDataObjects[i][j] = null;
                    }
                }
            }    
        }
        final Object[][] rows = tablleDataObjects;
        final String headers[] = headerNames.toArray(new String[headerNames.size()]);
        SwingThread.RunOnEdt(() -> {
            if(rows != null){
                DefaultTableModel model = (DefaultTableModel)contentTable.getModel();
                model.setDataVector(rows, headers);
            }
            else{
                ToggleContentTable(contentTable, false);
            }
            alignHeadersLeading(contentTable);
            if(headerNames.contains("Queue depth status")){
                contentTable.getColumn("Queue depth status").setCellRenderer(new DepthCellRender());
            }
            resizeColumnWidth(contentTable);
            contentTable.getTableHeader().setReorderingAllowed(false); 
        });
    }

    private static TableContentType getTableContentType(Object data){
        if(data instanceof QueueDetailModel){
            return TableContentType.QueueList;
        }
        else if(data instanceof ChannelDetailModel){
            return TableContentType.ChannelList;
        }
        else if(data instanceof MessageDetailModel){
            return TableContentType.MessageList;
        }
        else if(data instanceof ChannelAuthDetailModel){
            return TableContentType.ChannelAuthList;
        }
        else if(data instanceof PubDetailModel){
            return TableContentType.PubList;
        }
        else if(data instanceof SubDetailModel){
            return TableContentType.SubList;
        }
        return null;
    }

    public static void setType(TableListObject obj, Object data){
        try{
            obj.Type =  data.getClass().getField("Type").get(data);
        }catch(Exception ex){
            obj.Type = null;
            //LogWriter.WriteToLog(ex.getMessage(), LogType.Error);
        }
        
    }
    
    public static void ToggleContentTable(JTable contentTable, boolean show){
        SwingThread.RunOnEdt(() -> contentTable.setModel(new ContentTableModel()));
        //contentTable.setVisible(show);
   }
    
    public static TableListObject GetCurrentTableSelectRowObject(JTable contentTable){
        int selectedRow = contentTable.getSelectedRow();
        return GetTableSelectRowObject(contentTable, selectedRow);
    }
    
    public static TableListObject GetLastTableRowObject(JTable contentTable){
        int selectedRow = contentTable.getRowCount() - 1;
        return GetTableSelectRowObject(contentTable, selectedRow);
    }
    
    public static TableListObject GetTableSelectRowObject(JTable contentTable, int selectedRow){
        DefaultTableModel model = (DefaultTableModel)contentTable.getModel();
        if(selectedRow > -1){
            TableListObject objectModel = (TableListObject)model.getValueAt(contentTable.convertRowIndexToModel(selectedRow), 0);
            return objectModel;
        }
        return null;        
    }
    
    
    public static ArrayList<TableListObject> GetCurrentTableSelectRowObjects(JTable contentTable){
        DefaultTableModel model = (DefaultTableModel)contentTable.getModel();
        ArrayList<TableListObject> result = new ArrayList<TableListObject>();
        int[] selectedRows = contentTable.getSelectedRows();
        if(selectedRows != null && selectedRows.length > 0){
            for(int selectedRow : selectedRows){
                if(selectedRow > -1){
                    TableListObject objectModel = (TableListObject)model.getValueAt(contentTable.convertRowIndexToModel(selectedRow), 0);
                    result.add(objectModel);
                }            
            }
            return result;
        }
        return null;
    }
    
    public static void SetSelectedRow(JTable contentTable, Point point){
        int row = contentTable.rowAtPoint(point);

        if (! contentTable.isRowSelected(row)){
            contentTable.changeSelection(row, 0, false, false);
        }       
    }
       
    private static String toDisplayName(String name){
        return splitFieldName(toFirstLetterUppercase(name));
    }
    
    private static String toFirstLetterUppercase(String str){
        char[] stringArray = str.trim().toCharArray();
        stringArray[0] = Character.toUpperCase(stringArray[0]);
        return new String(stringArray);        
    }
    
    private static String splitFieldName(String name){
        String[] names = name.trim().split("(?=\\p{Upper})");
        String result = "";
        for(String s : names){
            result += s + " ";
        }
        return result.trim();
    }
    
    private static void resizeColumnWidth(JTable table) {
        final TableColumnModel columnModel = table.getColumnModel();
        for (int column = 0; column < table.getColumnCount(); column++) {
            int width = 150; // Min width
            int maxWidth = 300;
            // Measuring a sample is enough to size the column and keeps big lists fast
            int rows = Math.min(table.getRowCount(), RESIZE_SAMPLE_ROWS);
            for (int row = 0; row < rows && width < maxWidth; row++) {
                TableCellRenderer renderer = table.getCellRenderer(row, column);
                Component comp = table.prepareRenderer(renderer, row, column);
                width = Math.max(comp.getPreferredSize().width, width);
                width = width > maxWidth ? maxWidth : width;
            }
            // The grid lines take part of the column width: without this the longest values get truncated
            columnModel.getColumn(column).setPreferredWidth(width + table.getIntercellSpacing().width + 2);
        }
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    }
    
}
