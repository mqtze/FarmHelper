package com.github.may2beez.farmhelperv2.util.helper;

import net.minecraft.entity.Entity;
import net.minecraft.util.BlockPos;
import net.minecraft.util.Vec3;

import java.util.Optional;

public class Target {
    private Vec3 vec;
    private Entity entity;

    public Target(Vec3 vec) {
        this.vec = vec;
    }

    public Target(Entity entity) {
        this.entity = entity;
    }

    public Target(BlockPos blockPos) {
        this.vec = new Vec3(blockPos.getX() + 0.5, blockPos.getY() + 0.5, blockPos.getZ() + 0.5);
    }

    public Optional<Vec3> getTarget() {
        if (vec != null) {
            return Optional.of(vec);
        } else if (entity != null) {
            return Optional.of(entity.getPositionVector());
        } else {
            return Optional.empty();
        }
    }
}