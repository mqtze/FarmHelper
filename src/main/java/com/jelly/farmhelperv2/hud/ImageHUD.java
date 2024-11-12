package com.jelly.farmhelperv2.hud;

import cc.polyfrost.oneconfig.config.annotations.Info;
import cc.polyfrost.oneconfig.config.annotations.Switch;
import cc.polyfrost.oneconfig.config.annotations.Text;
import cc.polyfrost.oneconfig.config.core.OneColor;
import cc.polyfrost.oneconfig.config.data.InfoType;
import cc.polyfrost.oneconfig.hud.BasicHud;
import cc.polyfrost.oneconfig.libs.universal.UMatrixStack;
import cc.polyfrost.oneconfig.renderer.NanoVGHelper;

import com.jelly.farmhelperv2.config.FarmHelperConfig;
import com.jelly.farmhelperv2.handler.GameStateHandler;
import com.jelly.farmhelperv2.handler.MacroHandler;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URL;

import static java.lang.Math.min;


public class ImageHUD extends BasicHud {
    private float iconWidth = 100;
    private float iconHeight = 100;

    public ImageHUD() {
        super(false, 1f, 1f, 1, false, true, 4, 0, 0, new OneColor(0, 0, 0, 150), false, 0, new OneColor(0, 0, 0, 127));
    }

    @Info(
            text = "This can only render .jpg, .png and .svg files",
            type = InfoType.INFO, size = 2
    )
    public static boolean fileTypeInfo;
    @Text(
            name = "Image Path", placeholder = "Type the path of the image here",
            description = "This takes an url or file path",
            size = 2
    )
    public static String imagePath = "https://i.dailymail.co.uk/1s/2021/07/29/20/46061771-0-image-a-349_1627585586527.jpg";
    @Switch(
            name = "Show Image HUD outside the garden"
    )
    public static boolean showImageOutsideGarden = false;
    @Switch(
            name = "Show Image HUD only while farming"
    )
    public static boolean showImageOnlyWhileFarming = true;

    private void updateImageDimensions() {
        if (imagePath != null) {
            try {
                BufferedImage image;

                if (imagePath.startsWith("http://") || imagePath.startsWith("https://")) {
                    URL imageUrl = new URL(imagePath);
                    image = ImageIO.read(imageUrl);
                } else {
                    File imageFile = new File(imagePath);
                    image = ImageIO.read(imageFile);
                }

                if (image != null) {
                    float scale = 100f / min(image.getWidth(), image.getHeight());
                    this.iconWidth = image.getWidth() * scale;
                    this.iconHeight = image.getHeight() * scale;
                } else {
                    setDefaultDimensions();
                }
            } catch (Exception e) {
                setDefaultDimensions();
            }
        } else {
            setDefaultDimensions();
        }
    }

    private void setDefaultDimensions() {
        this.iconWidth = 100;
        this.iconHeight = 100;
    }

    @Override
    protected void draw(UMatrixStack matrices, float x, float y, float scale, boolean example) {
        updateImageDimensions();
        NanoVGHelper.INSTANCE.setupAndDraw(true, (vg) -> {
            if (imagePath != null) {
                NanoVGHelper.INSTANCE.drawImage(vg, imagePath, x, y, iconWidth * scale, iconHeight * scale, this.getClass());
            }
        });
    }

    @Override
    protected float getWidth(float scale, boolean example) {
        return iconWidth * scale;
    }

    @Override
    protected float getHeight(float scale, boolean example) {
        return iconHeight * scale;
    }

    @Override
    protected boolean shouldShow() {
        if (!super.shouldShow()) {
            return false;
        }
        return !FarmHelperConfig.streamerMode &&
                (GameStateHandler.getInstance().inGarden() || showImageOutsideGarden) &&
                (MacroHandler.getInstance().isMacroToggled() || !showImageOnlyWhileFarming);
    }
}