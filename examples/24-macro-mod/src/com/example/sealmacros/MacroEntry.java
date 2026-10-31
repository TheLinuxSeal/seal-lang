package com.example.sealmacros;

/**
 * What a macro is to the mod: the Seal API's Macro class implements it
 * (resources/sealmacros/api/macro/Macro.seal), and every macro extends Macro. The mod makes a
 * macro's class with new(), and starts it on the macro's own thread (MacroRun).
 */
public interface MacroEntry {
    void start(MacroRun run);
}
