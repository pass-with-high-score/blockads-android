package app.pwhs.blockads.service.tailscale;

interface ITailscaleService {
    String getTailscaleStatus();
    String configureTailscale(String stateJson, String controlUrl, String authKey);
    void startTailscaleSession(String hostname);
    void stopTailscale();
    void setTailscaleExitNode(long nodeId);
    void setTailscaleAdvertiseExitNode(boolean enabled);
    void setTailscaleAdBlockEnabled(boolean enabled);
    String sendTailscaleFile(String target, String fileName, String filePath);
    String pingTailscalePeer(String target);
}
