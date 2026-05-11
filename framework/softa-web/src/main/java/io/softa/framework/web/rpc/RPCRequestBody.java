package io.softa.framework.web.rpc;

import java.io.Serial;
import java.io.Serializable;
import lombok.Data;

import io.softa.framework.base.context.Context;

@Data
public class RPCRequestBody implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Object[] methodArgs;
    private Context context;
}
