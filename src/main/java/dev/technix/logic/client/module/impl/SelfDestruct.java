package dev.technix.logic.client.module.impl;

import dev.technix.logic.client.LogicClient;
import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;

 
public final class SelfDestruct extends Module {
    public SelfDestruct() { super("Self Destruct", "Unload Logic safely. The injector can then start a fresh session.", Category.PLAYER); }
    @Override public boolean actionOnly() { return true; }
    @Override protected void onEnable() { LogicClient.requestShutdown(); }
}
