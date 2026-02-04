package io.softa.framework.web.rpc;

import io.softa.framework.base.context.Context;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class RpcRequestBody implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Object[] methodArgs;
    private Context context;
}
