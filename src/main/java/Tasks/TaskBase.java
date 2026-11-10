/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package Tasks;

import Tasks.TaskInterface.StopableTask;
import UI.Helpers.SwingThread;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;

/**
 *
 * @author jzhou
 */
public abstract class TaskBase implements Runnable, StopableTask {
    ArrayList<ActionListener> ActionSuccessListeners = new ArrayList<ActionListener>();
    ArrayList<ActionListener> ActionFailListeners = new ArrayList<ActionListener>();
    protected boolean stopTask = false;
    public void AddTaskActionSuccessListener(ActionListener listener){
        ActionSuccessListeners.add(listener);
    }
    public void AddTaskActionFailListener(ActionListener listener){
        ActionFailListeners.add(listener);
    }   
    
    @Override
    public void StopTask(){
        stopTask = true;
    }
    
    // Listeners update the UI, so they run on the Swing event thread
    protected void FireActionSuccessEvent(){
        SwingThread.RunOnEdt(() -> {
            int id = 0;
            for(ActionListener listener : ActionSuccessListeners){
                ActionEvent evt = new ActionEvent(this, id, null);
                listener.actionPerformed(evt);
                id++;
            }
        });
    }
    // Listeners update the UI, so they run on the Swing event thread
    protected void FireActionFailEvent(){
        SwingThread.RunOnEdt(() -> {
            int id = 0;
            for(ActionListener listener : ActionFailListeners){
                ActionEvent evt = new ActionEvent(this, id, null);
                listener.actionPerformed(evt);
                id++;
            }
        });
    } 
}
