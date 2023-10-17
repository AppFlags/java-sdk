package io.appflags.sdk.utils;

import io.appflags.protos.PlatformData;

public class PlatformDataUtil {

    public static PlatformData getPlatformData() {
        String sdkVersion = PlatformDataUtil.class.getPackage().getImplementationVersion();
        String javaVersion = System.getProperty("java.version");
        return PlatformData.newBuilder()
            .setSdk("Java")
            .setSdkType("server")
            .setSdkVersion(sdkVersion)
            .setPlatform("Java")
            .setPlatformVersion(javaVersion)
            .build();
    }
}
