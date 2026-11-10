/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package MQApi.Utilities;

/**
 *
 * @author jzhou
 */
public class ThreadFinish {
    // Written by the worker thread and polled by another one
    public volatile boolean IsFinish;
}
