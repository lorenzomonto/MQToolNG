/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package MQApi.Models;

/**
 *
 * @author jzhou
 */
public class MQMessageIdModel {
    public byte[] MessageId;
    public byte[] CorrelationdId;
    public Integer position;
    /**
     * Token the queue manager gives each message on the queue: unique also when applications put
     * several messages with the same message and correlation id. Null when not known.
     */
    public byte[] MessageToken;
}
