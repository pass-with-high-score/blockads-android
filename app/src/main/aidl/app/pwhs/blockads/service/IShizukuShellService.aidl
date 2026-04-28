package app.pwhs.blockads.service;

interface IShizukuShellService {
    void destroy() = 16777114;
    void exit() = 16777113;
    String execCommand(String command) = 1;
}
