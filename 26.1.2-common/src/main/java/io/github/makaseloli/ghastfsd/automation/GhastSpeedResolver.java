package io.github.makaseloli.ghastfsd.automation;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.item.component.CustomData;

final class GhastSpeedResolver {
    private static final double VANILLA_FLYING_SPEED = 0.05;
    private static final double RIDDEN_SPEED_MULTIPLIER = 3.9;
    private static final double MIN_AUTOPILOT_SPEED = 0.08;
    private static final double MAX_AUTOPILOT_SPEED = 0.75;
    private static final String SPEED_UPGRADES_LEVEL_KEY = "speedupgrades_speed_level";
    private static final double[] SPEED_UPGRADES_FLYING_SPEED = {0.05, 0.075, 0.105, 0.14};

    private GhastSpeedResolver() {}

    static double cruiseSpeed() {
        return VANILLA_FLYING_SPEED * RIDDEN_SPEED_MULTIPLIER;
    }

    static double speed(HappyGhast ghast) {
        double upgradeSpeed = speedUpgradesFlyingSpeed(ghast);
        AttributeInstance attribute = ghast.getAttribute(Attributes.FLYING_SPEED);
        if (attribute != null && upgradeSpeed > attribute.getBaseValue()) {
            attribute.setBaseValue(upgradeSpeed);
        }
        double flyingSpeed = Math.max(ghast.getAttributeValue(Attributes.FLYING_SPEED), upgradeSpeed);
        return clamp(flyingSpeed * RIDDEN_SPEED_MULTIPLIER, MIN_AUTOPILOT_SPEED, MAX_AUTOPILOT_SPEED);
    }

    static double speedUpgradesFlyingSpeed(HappyGhast ghast) {
        int level = speedUpgradesLevel(ghast);
        if (level <= 0) {
            return 0.0;
        }
        return SPEED_UPGRADES_FLYING_SPEED[Math.min(level, SPEED_UPGRADES_FLYING_SPEED.length - 1)];
    }

    static int speedUpgradesLevel(HappyGhast ghast) {
        CustomData data = ghast.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) {
            return 0;
        }
        CompoundTag tag = data.copyTag();
        return clamp(tag.getIntOr(SPEED_UPGRADES_LEVEL_KEY, 0), 0, SPEED_UPGRADES_FLYING_SPEED.length - 1);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
