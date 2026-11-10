package dev.technix.logic;

import dev.technix.logic.inject.LiveTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.List;

public final class InjectionChecks {
    public static void main(String[] args) throws Exception {
        ClassLoader loader = InjectionChecks.class.getClassLoader();
        for (boolean mica : new boolean[]{false, true}) for (String target : LiveTransformer.TARGETS) {
            byte[] original;
            try (var stream = loader.getResourceAsStream(target.replace('.', '/') + ".class")) {
                if (stream == null) throw new AssertionError("Missing " + target);
                original = stream.readAllBytes();
            }
            byte[] transformed = LiveTransformer.transform(original, loader, mica);
            ClassNode before = read(original), after = read(transformed);
            if (!before.interfaces.equals(after.interfaces) || !fields(before).equals(fields(after))
                    || !methods(before).equals(methods(after))) throw new AssertionError("Class schema changed: " + target);
            for (var method : after.methods) {
                if (method.instructions.size() > 0) new Analyzer<>(new BasicVerifier()).analyze(after.name, method);
                for (var instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call && call.owner.equals("dev/technix/logic/host/RuntimeHost")) {
                        boolean matches = java.util.Arrays.stream(dev.technix.logic.host.RuntimeHost.class.getDeclaredMethods())
                            .anyMatch(bridge -> bridge.getName().equals(call.name) && org.objectweb.asm.Type.getMethodDescriptor(bridge).equals(call.desc));
                        if (!matches) throw new AssertionError("Missing runtime bridge ABI: " + call.name + call.desc);
                    }
                }
            }
            boolean hasHooks = after.methods.stream().anyMatch(method -> {
                for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("dev/technix/logic/host/RuntimeHost")) return true;
                return false;
            });
            if (!hasHooks && (mica || !target.endsWith("GuiRenderer"))) throw new AssertionError("No hooks: " + target);
            if (hasHooks) {
                boolean rejected = false;
                try { LiveTransformer.transform(transformed, loader, mica); }
                catch (IllegalStateException expected) { rejected = true; }
                if (!rejected) throw new AssertionError("Duplicate hook installation accepted");
            }
        }
         
        byte[] minecraft;
        try (var stream = loader.getResourceAsStream("net/minecraft/client/Minecraft.class")) {
            minecraft = stream.readAllBytes();
        }
        ClassNode lunar = read(minecraft);
        MethodNode keybinds = lunar.methods.stream().filter(method -> method.name.equals("handleKeybinds")).findFirst().orElseThrow();
        int attackReads = 0;
        boolean wrappedClick = false;
        for (var insn : keybinds.instructions) {
            if (insn instanceof org.objectweb.asm.tree.FieldInsnNode field && field.name.equals("keyAttack") && ++attackReads == 2) {
                var call = (MethodInsnNode) field.getNext();
                call.setOpcode(Opcodes.INVOKESTATIC);
                call.owner = lunar.name;
                call.name = "lunarWrappedAttackClick";
                call.desc = "(Lnet/minecraft/client/KeyMapping;)Z";
                wrappedClick = true;
                break;
            }
        }
        if (!wrappedClick) throw new AssertionError("Missing attack click fixture");
        ClassWriter lunarWriter = new ClassWriter(0);
        lunar.accept(lunarWriter);
        ClassNode lunarTransformed = read(LiveTransformer.transform(lunarWriter.toByteArray(), loader, true));
        MethodNode lunarKeybinds = lunarTransformed.methods.stream().filter(method -> method.name.equals("handleKeybinds")).findFirst().orElseThrow();
        boolean lunarHook = false;
        for (var insn : lunarKeybinds.instructions) {
            if (insn instanceof MethodInsnNode call && call.name.equals("lunarWrappedAttackClick")) {
                lunarHook = call.getNext() instanceof MethodInsnNode hook && hook.name.equals("attackClick");
            }
        }
        if (!lunarHook) throw new AssertionError("Lunar wrapped attack click was not hooked");
        new Analyzer<>(new BasicVerifier()).analyze(lunarTransformed.name, lunarKeybinds);

        byte[] renderer;
        try (var stream = loader.getResourceAsStream("net/minecraft/client/renderer/GameRenderer.class")) {
            renderer = stream.readAllBytes();
        }
        ClassNode wrapped = read(renderer);
        String projectionDescriptor = "(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;";
        String wrapperDescriptor = "(Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;";
        boolean replaced = false;
        for (var method : wrapped.methods) {
            if (!method.name.equals("renderLevel")) continue;
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals("net/minecraft/client/renderer/ProjectionMatrixBuffer")
                        && call.name.equals("getBuffer") && call.desc.equals(projectionDescriptor)) {
                    call.setOpcode(Opcodes.INVOKESTATIC);
                    call.owner = wrapped.name;
                    call.name = "sodium$wrappedProjection";
                    call.desc = wrapperDescriptor;
                    replaced = true;
                }
            }
        }
        if (!replaced) throw new AssertionError("Sodium regression fixture did not replace the projection call");
        MethodNode wrapper = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
            "sodium$wrappedProjection", wrapperDescriptor, null, null);
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
            "net/minecraft/client/renderer/ProjectionMatrixBuffer", "getBuffer", projectionDescriptor, false));
        wrapper.instructions.add(new InsnNode(Opcodes.ARETURN));
        wrapper.maxStack = 2;
        wrapper.maxLocals = 2;
        wrapped.methods.add(wrapper);
        ClassWriter writer = new ClassWriter(0);
        wrapped.accept(writer);
        byte[] modified = LiveTransformer.transform(writer.toByteArray(), loader, true);
        ClassNode after = read(modified);
        if (!fields(wrapped).equals(fields(after)) || !methods(wrapped).equals(methods(after))) {
            throw new AssertionError("Sodium-style renderer schema changed");
        }
        for (var method : after.methods) if (method.instructions.size() > 0) {
            new Analyzer<>(new BasicVerifier()).analyze(after.name, method);
        }
        System.out.println("Injection checks passed: " + LiveTransformer.TARGETS.length + " Minecraft 26.2 targets, Sodium-style projection wrapping, Mica modes, bytecode verification, schema preservation and duplicate rejection.");
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        return node;
    }
    private static List<String> fields(ClassNode node) { return node.fields.stream().map(f -> f.name + f.desc + f.access).toList(); }
    private static List<String> methods(ClassNode node) { return node.methods.stream().map(m -> m.name + m.desc + m.access).toList(); }
}
