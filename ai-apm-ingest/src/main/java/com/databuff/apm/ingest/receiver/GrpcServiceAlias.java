package com.databuff.apm.ingest.receiver;

import io.grpc.BindableService;
import io.grpc.MethodDescriptor;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;

/** Binds one gRPC implementation under an additional wire-level service name. */
final class GrpcServiceAlias {

    private GrpcServiceAlias() {
    }

    static ServerServiceDefinition withServiceName(BindableService service, String serviceName) {
        ServerServiceDefinition.Builder alias = ServerServiceDefinition.builder(serviceName);
        for (ServerMethodDefinition<?, ?> method : service.bindService().getMethods()) {
            addAliasedMethod(alias, serviceName, method);
        }
        return alias.build();
    }

    private static <RequestT, ResponseT> void addAliasedMethod(
            ServerServiceDefinition.Builder alias,
            String serviceName,
            ServerMethodDefinition<RequestT, ResponseT> source) {
        MethodDescriptor<RequestT, ResponseT> sourceDescriptor = source.getMethodDescriptor();
        MethodDescriptor<RequestT, ResponseT> aliasDescriptor = sourceDescriptor.toBuilder()
                .setFullMethodName(MethodDescriptor.generateFullMethodName(
                        serviceName,
                        sourceDescriptor.getBareMethodName()))
                .build();
        alias.addMethod(aliasDescriptor, source.getServerCallHandler());
    }
}
