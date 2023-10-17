package io.appflags.sdk.utils;

import io.appflags.protos.ComputedFlag;
import io.appflags.protos.FlagValueType;
import io.appflags.protos.User;
import io.appflags.sdk.exceptions.AppFlagsException;
import io.appflags.sdk.models.AppFlagsFlag;
import io.appflags.sdk.models.AppFlagsUser;

public class ProtobufConverter {

    private ProtobufConverter() {}

    public static User toProtoUser(final AppFlagsUser user) {
        return User.newBuilder()
            .setKey(user.getKey())
            .build();
    }

    public static AppFlagsFlag fromComputedFlag(final ComputedFlag flag) {
        switch (flag.getValueType()) {
            case BOOLEAN:
                return AppFlagsFlag.<Boolean>builder()
                    .key(flag.getKey())
                    .flagType(fromFlagValueType(flag.getValueType()))
                    .value(flag.getValue().getBooleanValue())
                    .build();
            case DOUBLE:
                return AppFlagsFlag.<Double>builder()
                    .key(flag.getKey())
                    .flagType(fromFlagValueType(flag.getValueType()))
                    .value(flag.getValue().getDoubleValue())
                    .build();
            case STRING:
                return AppFlagsFlag.<String>builder()
                    .key(flag.getKey())
                    .flagType(fromFlagValueType(flag.getValueType()))
                    .value(flag.getValue().getStringValue())
                    .build();
            case UNRECOGNIZED:
            default:
                throw new AppFlagsException("Unexpected FlagValueType " + flag.getValueType().name());
        }
    }

    private static AppFlagsFlag.FlagType fromFlagValueType(FlagValueType flagValueType) {
        switch (flagValueType) {
            case BOOLEAN:
                return AppFlagsFlag.FlagType.BOOLEAN;
            case DOUBLE:
                return AppFlagsFlag.FlagType.NUMBER;
            case STRING:
                return AppFlagsFlag.FlagType.STRING;
            case UNRECOGNIZED:
            default:
                throw new AppFlagsException("Unexpected FlagValueType " + flagValueType.name());
        }
    }
}
