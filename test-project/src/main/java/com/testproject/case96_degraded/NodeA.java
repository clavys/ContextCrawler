package com.testproject.case96_degraded;

// Référence circulaire NodeA<T> ↔ NodeB<T> — PSI doit gérer le cycle dans
// la résolution des génériques sans boucler (STRATEGIE.md §8bis.5).
public class NodeA<T> {
    private NodeB<T> peer;
    private T payload;

    public NodeB<T> getPeer() { return peer; }
    public void setPeer(NodeB<T> peer) { this.peer = peer; }
    public T getPayload() { return payload; }
    public void setPayload(T payload) { this.payload = payload; }
}
