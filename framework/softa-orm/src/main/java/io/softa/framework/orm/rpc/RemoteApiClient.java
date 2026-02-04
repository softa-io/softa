package io.softa.framework.orm.rpc;

public interface RemoteApiClient {

    <T> T modelRpc(String serviceName, String modelName, String methodName, Object[] methodArgs);

}