package com.fnfmod.client.lua;

import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

/** Bounded conversion between persistent NBT and Lua machineData tables. */
final class MachineLuaData {

    private static final int MAX_DEPTH = 8;
    private static final int MAX_ENTRIES = 1024;

    private MachineLuaData() {}

    static LuaTable toLua(CompoundTag tag) {
        int[] count = {0};
        return toLua(tag == null ? new CompoundTag() : tag, 0, count);
    }

    private static LuaTable toLua(CompoundTag tag, int depth, int[] count) {
        LuaTable table = new LuaTable();
        if (depth >= MAX_DEPTH) return table;
        for (String key : tag.getAllKeys()) {
            if (++count[0] > MAX_ENTRIES) break;
            Tag value = tag.get(key);
            if (value instanceof CompoundTag compound) {
                table.set(key, toLua(compound, depth + 1, count));
            } else if (value instanceof ByteTag byteTag
                    && (byteTag.getAsByte() == 0 || byteTag.getAsByte() == 1)) {
                table.set(key, LuaValue.valueOf(byteTag.getAsByte() != 0));
            } else if (value instanceof NumericTag number) {
                table.set(key, LuaValue.valueOf(number.getAsDouble()));
            } else if (value != null) {
                table.set(key, LuaValue.valueOf(value.getAsString()));
            }
        }
        return table;
    }

    static CompoundTag toNbt(LuaTable table) {
        int[] count = {0};
        return toNbt(table, 0, count);
    }

    private static CompoundTag toNbt(LuaTable table, int depth, int[] count) {
        CompoundTag tag = new CompoundTag();
        if (table == null || depth >= MAX_DEPTH) return tag;
        LuaValue key = LuaValue.NIL;
        while (true) {
            Varargs next = table.next(key);
            key = next.arg1();
            if (key.isnil() || ++count[0] > MAX_ENTRIES) break;
            String name = key.tojstring();
            if (name.length() > 128) continue;
            LuaValue value = next.arg(2);
            if (value.istable()) {
                tag.put(name, toNbt(value.checktable(), depth + 1, count));
            } else if (value.isboolean()) {
                tag.put(name, ByteTag.valueOf(value.toboolean()));
            } else if (value.isnumber()) {
                tag.put(name, DoubleTag.valueOf(value.todouble()));
            } else if (value.isstring()) {
                String text = value.tojstring();
                tag.put(name, StringTag.valueOf(text.length() > 4096 ? text.substring(0, 4096) : text));
            }
        }
        return tag;
    }
}
