package org.esa.beam.glevel;

import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.awt.image.RenderedImage;

import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.jai.ImageManager;

import com.bc.ceres.core.Assert;
import com.bc.ceres.glevel.MultiLevelModel;
import com.bc.ceres.glevel.MultiLevelSource;
import com.bc.ceres.glevel.support.AbstractMultiLevelSource;
import com.bc.ceres.glevel.support.DefaultMultiLevelModel;

public class MaskImageMultiLevelSource extends AbstractMultiLevelSource {

    private final Product product;
    private final Color color;
    private final String expression;
    private final boolean inverseMask;

    public static MultiLevelSource create(Product product, Color color, String expression,
                                          boolean inverseMask, AffineTransform i2mTransform) {
        Assert.notNull(product);
        Assert.notNull(color);
        Assert.notNull(expression);
        final int width = product.getSceneRasterWidth();
        final int height = product.getSceneRasterHeight();
        final int levelCount = ImageManager.computeMaxLevelCount(width, height);
        MultiLevelModel model = new DefaultMultiLevelModel(levelCount, i2mTransform,
                                                           width, height);
        return new MaskImageMultiLevelSource(model, product, color, expression, inverseMask);
    }

    public MaskImageMultiLevelSource(MultiLevelModel model, Product product, Color color,
                                      String expression, boolean inverseMask) {
        super(model);
        this.product = product;
        this.color = color;
        this.expression = expression;
        this.inverseMask = inverseMask;
    }

    @Override
    public RenderedImage createImage(int level) {
        return ImageManager.getInstance().createColoredMaskImage(product, expression, color,
                                                                 inverseMask, level);
    }
}