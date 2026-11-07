package dev.technix.logic.inject;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.function.Consumer;


public final class LiveTransformer implements Opcodes {
    private static final String HOOKS = "dev/technix/logic/host/RuntimeHost";
    public static final String[] TARGETS = {
        "net.minecraft.client.KeyboardHandler", "net.minecraft.client.MouseHandler",
        "net.minecraft.client.renderer.GameRenderer", "net.minecraft.client.Minecraft",
        "net.minecraft.client.multiplayer.MultiPlayerGameMode", "net.minecraft.network.PacketProcessor$ListenerAndPacket",
        "net.minecraft.client.gui.render.GuiRenderer", "net.minecraft.client.renderer.ProjectionMatrixBuffer",
        "net.minecraft.client.renderer.entity.player.AvatarRenderer", "net.minecraft.network.Connection",
        "net.minecraft.client.gui.Font"
    };

    private LiveTransformer() { }

    public static byte[] transform(byte[] bytes, ClassLoader loader) {
        return transform(bytes, loader, true);
    }

    public static byte[] transform(byte[] bytes, ClassLoader loader, boolean injectMica) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        for (MethodNode method : node.methods) for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(HOOKS)) {
                throw new IllegalStateException("Logic injection hooks are already installed in " + node.name);
            }
        }
        switch (node.name) {
            case "net/minecraft/client/gui/Font" -> {
                for (MethodNode method : node.methods) {
                    if (!(method.name.equals("prepareText") || method.name.equals("prepare8xTextOutline")
                            || method.name.equals("width"))) continue;
                    var argument = org.objectweb.asm.Type.getArgumentTypes(method.desc)[0];
                    InsnList rewrite = new InsnList();
                    rewrite.add(new VarInsnNode(ALOAD, 1));
                    rewrite.add(call("rewriteDisplayText", "(Ljava/lang/Object;)Ljava/lang/Object;"));
                    rewrite.add(new TypeInsnNode(CHECKCAST, argument.getInternalName()));
                    rewrite.add(new VarInsnNode(ASTORE, 1));
                    method.instructions.insert(rewrite);
                }
            }
            case "net/minecraft/client/KeyboardHandler" -> {
                MethodNode method = method(node, "keyPress", "(JILnet/minecraft/client/input/KeyEvent;)V");
                guard(method, "keyboard", "(ILnet/minecraft/client/input/KeyEvent;)Z", insns -> {
                    insns.add(new VarInsnNode(ILOAD, 3)); insns.add(new VarInsnNode(ALOAD, 4));
                });
                guard(method(node, "charTyped", "(JLnet/minecraft/client/input/CharacterEvent;)V"),
                    "character", "(Lnet/minecraft/client/input/CharacterEvent;)Z", insns -> insns.add(new VarInsnNode(ALOAD, 3)));
            }
            case "net/minecraft/client/MouseHandler" -> {
                if (injectMica) {
                    InsnList head = new InsnList();
                    head.add(new VarInsnNode(DLOAD, 3)); head.add(new VarInsnNode(DLOAD, 5));
                    head.add(call("mouseMove", "(DD)V"));
                    method(node, "onMove", "(JDD)V").instructions.insert(head);
                }
                guard(method(node, "onButton", "(JLnet/minecraft/client/input/MouseButtonInfo;I)V"), "mouse",
                    "(Lnet/minecraft/client/input/MouseButtonInfo;I)Z", insns -> {
                        insns.add(new VarInsnNode(ALOAD, 3)); insns.add(new VarInsnNode(ILOAD, 4));
                    });
                guard(method(node, "onScroll", "(JDD)V"), "scroll", "(DD)Z", insns -> {
                    insns.add(new VarInsnNode(DLOAD, 3)); insns.add(new VarInsnNode(DLOAD, 5));
                });
            }
            case "net/minecraft/client/renderer/GameRenderer" -> {
                InsnList head = new InsnList();
                head.add(new VarInsnNode(ALOAD, 1));
                head.add(call("frame", "(Lnet/minecraft/client/DeltaTracker;)V"));
                method(node, "update", "(Lnet/minecraft/client/DeltaTracker;)V").instructions.insert(head);
                 
            }
            case "net/minecraft/client/renderer/ProjectionMatrixBuffer" -> {
                InsnList capture = new InsnList();
                capture.add(new VarInsnNode(ALOAD, 0));
                capture.add(new VarInsnNode(ALOAD, 1));
                capture.add(call("projection", "(Ljava/lang/Object;Lorg/joml/Matrix4f;)V"));
                method(node, "getBuffer", "(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;")
                    .instructions.insert(capture);
            }
            case "net/minecraft/client/Minecraft" -> {
                MethodNode tickDuration = method(node, "getTickTargetMillis", "(F)F");
                for (AbstractInsnNode insn : tickDuration.instructions.toArray()) {
                    if (insn.getOpcode() == FRETURN) tickDuration.instructions.insertBefore(insn, call("tickDuration", "(F)F"));
                }
                MethodNode keybinds = method(node, "handleKeybinds", "()V");
                InsnList head = new InsnList();
                head.add(call("keybinds", "()V"));
                keybinds.instructions.insert(head);
                hookAttackClick(keybinds);
                for (AbstractInsnNode insn : keybinds.instructions.toArray()) {
                    if (insn.getOpcode() == RETURN) keybinds.instructions.insertBefore(insn, call("afterKeybinds", "()V"));
                }
                guard(method(node, "startUseItem", "()V"), "useItem", "()Z", insns -> { });
                InsnList attackGuard = new InsnList();
                LabelNode attackAllowed = new LabelNode();
                attackGuard.add(call("blockAttack", "()Z"));
                attackGuard.add(new JumpInsnNode(IFEQ, attackAllowed));
                attackGuard.add(new InsnNode(ICONST_0));
                attackGuard.add(new InsnNode(IRETURN));
                attackGuard.add(attackAllowed);
                method(node, "startAttack", "()Z").instructions.insert(attackGuard);
            }
            case "net/minecraft/client/multiplayer/MultiPlayerGameMode" -> {
                InsnList head = new InsnList();
                head.add(new VarInsnNode(ALOAD, 2));
                head.add(call("attack", "(Lnet/minecraft/world/entity/Entity;)V"));
                method(node, "attack", "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;)V").instructions.insert(head);
            }
            case "net/minecraft/client/renderer/entity/player/AvatarRenderer" -> {
                InsnList head = new InsnList();
                LabelNode proceed = new LabelNode();
                head.add(new VarInsnNode(ALOAD, 1));
                head.add(call("hideVanillaNametag", "(Ljava/lang/Object;)Z"));
                head.add(new JumpInsnNode(IFEQ, proceed));
                head.add(new InsnNode(ICONST_0));
                head.add(new InsnNode(IRETURN));
                head.add(proceed);
                method(node, "shouldShowName", "(Lnet/minecraft/world/entity/Avatar;D)Z").instructions.insert(head);
            }
            case "net/minecraft/network/Connection" -> {
                MethodNode send = method(node, "send", "(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V");
                guard(send,
                    "outgoing", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Z)Z", insns -> {
                        insns.add(new VarInsnNode(ALOAD, 0));
                        insns.add(new VarInsnNode(ALOAD, 1));
                        insns.add(new VarInsnNode(ALOAD, 2));
                        insns.add(new VarInsnNode(ILOAD, 3));
                    });
                InsnList rewrite = new InsnList();
                rewrite.add(new VarInsnNode(ALOAD, 1));
                rewrite.add(call("rewriteOutgoing", "(Ljava/lang/Object;)Ljava/lang/Object;"));
                rewrite.add(new TypeInsnNode(CHECKCAST, "net/minecraft/network/protocol/Packet"));
                rewrite.add(new VarInsnNode(ASTORE, 1));
                send.instructions.insert(rewrite);
            }
            case "net/minecraft/network/PacketProcessor$ListenerAndPacket" -> {
                guard(method(node, "handle", "()V"), "packet",
                    "(Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/protocol/Packet;)Z", insns -> {
                        insns.add(new VarInsnNode(ALOAD, 0));
                        insns.add(new FieldInsnNode(GETFIELD, node.name, "listener", "Lnet/minecraft/network/PacketListener;"));
                        insns.add(new VarInsnNode(ALOAD, 0));
                        insns.add(new FieldInsnNode(GETFIELD, node.name, "packet", "Lnet/minecraft/network/protocol/Packet;"));
                    });
            }
            case "net/minecraft/client/gui/render/GuiRenderer" -> {
                if (injectMica) {
                    MethodNode render = method(node, "render", "()V");
                    InsnList head = new InsnList();
                    head.add(call("beforeGuiRender", "()V"));
                    render.instructions.insert(head);
                    for (AbstractInsnNode insn : render.instructions.toArray()) {
                        if (insn.getOpcode() == RETURN) render.instructions.insertBefore(insn, call("afterGuiRender", "()V"));
                    }
                }
            }
            default -> throw new IllegalArgumentException("Unsupported target " + node.name);
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected ClassLoader getClassLoader() { return loader; }
        };
        node.accept(writer);
        return writer.toByteArray();
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst()
            .orElseThrow(() -> new IllegalStateException("Unsupported game build: " + node.name + "." + name + desc));
    }

    private static void hookAttackClick(MethodNode keybinds) {
         
        MethodInsnNode attackClick = null;
        int attackReads = 0;
        for (AbstractInsnNode insn : keybinds.instructions) {
            if (insn instanceof FieldInsnNode key && key.getOpcode() == GETFIELD
                    && key.owner.equals("net/minecraft/client/Options") && key.name.equals("keyAttack")) {
                if (++attackReads == 2) {
                    AbstractInsnNode next = key.getNext();
                    while (next != null && next.getOpcode() < 0) next = next.getNext();
                    if (!(next instanceof MethodInsnNode consume) || !consume.owner.equals("net/minecraft/client/KeyMapping")
                            || !consume.name.equals("consumeClick") || !consume.desc.equals("()Z")) break;
                    attackClick = consume;
                } else if (attackReads > 2) break;
            }
            if (attackClick != null && insn instanceof MethodInsnNode attack && attack.owner.equals("net/minecraft/client/Minecraft")
                    && attack.name.equals("startAttack") && attack.desc.equals("()Z")) {
                keybinds.instructions.insert(attackClick, call("attackClick", "(Z)Z"));
                return;
            }
        }
        throw new IllegalStateException("Vanilla attack click loop was not found");
    }

    private static MethodInsnNode call(String name, String descriptor) {
        return new MethodInsnNode(INVOKESTATIC, HOOKS, name, descriptor.replaceAll("L[^;]+;", "Ljava/lang/Object;"), false);
    }

    private static void guard(MethodNode method, String name, String desc, Consumer<InsnList> arguments) {
        InsnList head = new InsnList();
        arguments.accept(head);
        head.add(call(name, desc));
        LabelNode proceed = new LabelNode();
        head.add(new JumpInsnNode(IFEQ, proceed));
        head.add(new InsnNode(RETURN));
        head.add(proceed);
        method.instructions.insert(head);
    }
}
