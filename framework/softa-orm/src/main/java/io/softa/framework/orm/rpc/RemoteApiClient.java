package io.softa.framework.orm.rpc;

public interface RemoteApiClient {

    <T> T modelRPC(String serviceName, String modelName, String methodName, Object[] methodArgs);

}