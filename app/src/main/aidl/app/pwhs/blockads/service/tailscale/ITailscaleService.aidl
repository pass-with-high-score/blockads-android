package app.pwhs.blockads.service.tailscale;

interface ITailscaleService {
    String getTailscaleStatus();
    String configureTailscale(String stateJson, String controlUrl, String authKey);
    void startTailscaleSession(String hostname);
    void stopTailscale();
    void setTailscaleExitNode(long nodeId);
    String pingTailscalePeer(String target);
}
