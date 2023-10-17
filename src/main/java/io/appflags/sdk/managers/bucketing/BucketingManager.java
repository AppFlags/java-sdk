package io.appflags.sdk.managers.bucketing;

import com.google.protobuf.InvalidProtocolBufferException;
import io.appflags.protos.BucketingResult;
import io.appflags.protos.Configuration;
import io.appflags.protos.User;
import io.appflags.sdk.exceptions.AppFlagsException;
import io.github.kawamuray.wasmtime.*;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static io.github.kawamuray.wasmtime.WasmValType.I32;

public class BucketingManager {

    private static final Logger logger = LoggerFactory.getLogger(BucketingManager.class);

    private static final int ARRAY_BUFFER_CLASS_ID = 1;
    private static final int UINT8_ARRAY_CLASS_ID = 8;
    private static final int UINT8_ARRAY_LENGTH = 12;

    private final Store<Void> store;
    private final Engine engine;
    private final Linker linker;
    private final Module module;
    private final Memory memory;
    private final List<Integer> pinnedPointers = new ArrayList<>();

    public BucketingManager() {
        store = Store.withoutData();
        engine = store.engine();
        linker = new Linker(engine);

        try {
            final InputStream wasm = BucketingManager.class.getResourceAsStream("/release.wasm");
            module = Module.fromBinary(engine, IOUtils.toByteArray(wasm));
        } catch (IOException e) {
            throw new AppFlagsException("Error reading wasm module", e);
        }

        initImportsOnLinker(); //
        linker.module(store, "", module);
        memory = linker.get(store, "", "memory").get().memory();
    }

    private void initImportsOnLinker() {
        final Func abortFn = WasmFunctions.wrap(store, I32, I32, I32, I32, (messagePointer, filenamePointer, lineNum, columnNum) -> {
            final String message = readString(messagePointer);
            final String fileName = readString(filenamePointer);
            logger.error("WASM error in " + fileName + ":" + lineNum + " : " + columnNum + " " + message);
        });
        linker.define(store, "env", "abort", Extern.fromFunc(abortFn));

        final Func consoleLogFn = WasmFunctions.wrap(store, I32, (pointer) -> {
            final String log = readString(pointer);
            logger.debug("WASM log: " + log);
        });
        linker.define(store, "env", "console.log", Extern.fromFunc(consoleLogFn));
    }

    public synchronized void setConfiguration(final Configuration config) {
        unpinAll();

        final byte[] configBytes = config.toByteArray();
        final int configPointer = writeUint8Array(configBytes);

        final Func setConfigFn = linker.get(store, "", "setConfiguration").get().func();
        final WasmFunctions.Consumer1<Integer> setConfig = WasmFunctions.consumer(
            store, setConfigFn, WasmValType.I32);
        setConfig.accept(configPointer);
    }

    public synchronized BucketingResult bucket(final User user) {
        unpinAll();

        final byte[] userBytes = user.toByteArray();
        final int userPointer = writeUint8Array(userBytes);

        final Func bucketFn = linker.get(store, "", "bucket").get().func();
        final WasmFunctions.Function1<Integer, Integer> bucket = WasmFunctions.func(
            store, bucketFn, WasmValType.I32, WasmValType.I32);

        final int resultPointer = bucket.call(userPointer);
        final byte[] resultBytes = readUint8Array(resultPointer);
        try {
            return BucketingResult.parseFrom(resultBytes);
        } catch (InvalidProtocolBufferException e) {
            throw new AppFlagsException("Error decoding BucketingResult proto", e);
        }
    }

    private String readString(final int pointer) {
        final ByteBuffer buffer = memory.buffer(store);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        final int length = buffer.getInt(pointer - 4);
        // char in Java is 2 bytes, so divide pointers by 2
        return buffer.asCharBuffer().subSequence(pointer / 2, (pointer + length) / 2).toString();
    }

    private byte[] readUint8Array(final int uint8ArrayPointer) {
        final ByteBuffer buffer = memory.buffer(store);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // pointer points to a Uint8Array, which is a pointer to an ArrayBuffer
        final int arrayBufferPointer = buffer.getInt(uint8ArrayPointer + 4);
        final int length = buffer.getInt(uint8ArrayPointer + 8);

        // read ArrayBuffer
        final byte[] data = new byte[length];
        for(int i = 0; i < length; i++)
        {
            data[i] = buffer.get(arrayBufferPointer + i);
        }
        return data;
    }

    private int writeUint8Array(byte[] array) {
        final Func __newPtr = linker.get(store, "", "__new").get().func();
        final WasmFunctions.Function2<Integer, Integer, Integer> __new = WasmFunctions.func(store, __newPtr, I32, I32, I32);

        final int arrayBufferPointer = __new.call(array.length, ARRAY_BUFFER_CLASS_ID);
        pin(arrayBufferPointer);
        final int uint8ArrayPointer = __new.call(UINT8_ARRAY_LENGTH, UINT8_ARRAY_CLASS_ID);
        pin(uint8ArrayPointer);

        final ByteBuffer buffer = memory.buffer(store);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // Uint8Array is 12 bytes
        buffer.putInt(uint8ArrayPointer, arrayBufferPointer);
        buffer.putInt(uint8ArrayPointer+4, arrayBufferPointer);
        buffer.putInt(uint8ArrayPointer+8, array.length);

        // put data into ArrayBuffer
        for (int i = 0; i < array.length; ++i) {
            buffer.put(arrayBufferPointer + i, array[i]);
        }

        return uint8ArrayPointer;
    }

    // pins an object so it won't be garbage collected
    private void pin(final int pointer) {
        final Func __pinPtr = linker.get(store, "", "__pin").get().func();
        final WasmFunctions.Consumer1<Integer> __pin = WasmFunctions.consumer(store, __pinPtr, I32);
        __pin.accept(pointer);
        pinnedPointers.add(pointer);
    }

    private void unpinAll() {
        for (int pointer: pinnedPointers) {
            unpin(pointer);
        }
        pinnedPointers.clear();
    }

    // unpins an object so it can be garbage collected
    private void unpin(final int pointer) {
        final Func __unpinPtr = linker.get(store, "", "__unpin").get().func();
        WasmFunctions.Consumer1<Integer> __unpin = WasmFunctions.consumer(store, __unpinPtr, I32);
        __unpin.accept(pointer);
    }

}
