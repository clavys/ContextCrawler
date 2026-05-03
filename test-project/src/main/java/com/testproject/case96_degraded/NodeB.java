package com.testproject.case96_degraded;

public class NodeB<T> {
    private NodeA<T> peer;
    private T payload;

    public NodeA<T> getPeer() { return peer; }
    public void setPeer(NodeA<T> peer) { this.peer = peer; }
    public T getPayload() { return payload; }
    public void setPayload(T payload) { this.payload = payload; }
}
