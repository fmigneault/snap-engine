package org.esa.beam.framework.gpf.operators.meris;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.beam.framework.datamodel.*;
import org.esa.beam.framework.gpf.*;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.util.ProductUtils;

import java.awt.Color;
import java.awt.Rectangle;
import java.util.Map;

/**
 * The <code>NdviOp</code> uses MERIS Level-1b TOA radiances of bands 6 and 10
 * to retrieve the Normalized Difference Vegetation Index (NDVI).
 *
 * @author Maximilian Aulinger
 */
public class NdviOp extends AbstractOperator {

    // constants
    public static final String NDVI_PRODUCT_TYPE = "MER_NDVI2P";
    public static final String NDVI_BAND_NAME = "ndvi";
    public static final String NDVI_FLAGS_BAND_NAME = "ndvi_flags";
    public static final String NDVI_ARITHMETIC_FLAG_NAME = "NDVI_ARITHMETIC";
    public static final String NDVI_LOW_FLAG_NAME = "NDVI_NEGATIVE";
    public static final String NDVI_HIGH_FLAG_NAME = "NDVI_SATURATION";
    public static final int NDVI_ARITHMETIC_FLAG_VALUE = 1;
    public static final int NDVI_LOW_FLAG_VALUE = 1 << 1;
    public static final int NDVI_HIGH_FLAG_VALUE = 1 << 2;
    public static final String L1FLAGS_INPUT_BAND_NAME = "l1_flags";
    public static final String LOWER_INPUT_BAND_NAME = "radiance_6";
    public static final String UPPER_INPUT_BAND_NAME = "radiance_10";
    private Band _lowerInputBand;
    private Band _upperInputBand;

    @SourceProduct(alias = "input")
    private Product inputProduct;
    @TargetProduct
    private Product targetProduct;

    public NdviOp(OperatorSpi spi) {
        super(spi);
    }

    @Override
    protected Product initialize(ProgressMonitor pm) throws OperatorException {
        loadSourceBands(inputProduct);
        int sceneWidth = inputProduct.getSceneRasterWidth();
        int sceneHeight = inputProduct.getSceneRasterHeight();
        // create the in memory represenation of the output product
        // ---------------------------------------------------------
        // the product itself
        targetProduct = new Product("ndvi", NDVI_PRODUCT_TYPE, sceneWidth, sceneHeight);

        // create and add the NDVI band
        Band ndviOutputBand = new Band(NDVI_BAND_NAME, ProductData.TYPE_FLOAT32, sceneWidth,
                                       sceneHeight);
        targetProduct.addBand(ndviOutputBand);

        // copy all tie point grids to output product
        ProductUtils.copyTiePointGrids(inputProduct, targetProduct);

        // copy geo-coding and the lat/lon tiepoints to the output product
        ProductUtils.copyGeoCoding(inputProduct, targetProduct);

        ProductUtils.copyFlagBands(inputProduct, targetProduct);

        // create and add the NDVI flags coding
        FlagCoding ndviFlagCoding = createNdviFlagCoding();
        targetProduct.addFlagCoding(ndviFlagCoding);

        // create and add the NDVI flags band
        Band ndviFlagsOutputBand = new Band(NDVI_FLAGS_BAND_NAME, ProductData.TYPE_INT32,
                                            sceneWidth, sceneHeight);
        ndviFlagsOutputBand.setDescription("NDVI specific flags");
        ndviFlagsOutputBand.setFlagCoding(ndviFlagCoding);
        targetProduct.addBand(ndviFlagsOutputBand);

        // Copy predefined bitmask definitions
        ProductUtils.copyBitmaskDefs(inputProduct, targetProduct);
        targetProduct.addBitmaskDef(new BitmaskDef(NDVI_ARITHMETIC_FLAG_NAME.toLowerCase(),
                                                   "An arithmetic exception occured.", NDVI_FLAGS_BAND_NAME + "."
                + NDVI_ARITHMETIC_FLAG_NAME, Color.red.brighter(), 0.7f));
        targetProduct.addBitmaskDef(new BitmaskDef(NDVI_LOW_FLAG_NAME.toLowerCase(),
                                                   "NDVI value is too low.", NDVI_FLAGS_BAND_NAME + "." + NDVI_LOW_FLAG_NAME,
                                                   Color.red, 0.7f));
        targetProduct.addBitmaskDef(new BitmaskDef(NDVI_HIGH_FLAG_NAME.toLowerCase(),
                                                   "NDVI value is too high.", NDVI_FLAGS_BAND_NAME + "." + NDVI_HIGH_FLAG_NAME,
                                                   Color.red.darker(), 0.7f));

        return targetProduct;
    }

    @Override
    public void computeAllBands(Map<Band, Raster> targetRasters, Rectangle rectangle, ProgressMonitor pm) throws OperatorException {

        pm.beginTask("Computing NDVI", rectangle.height + 1);
        try {

            Raster l1flagsSourceRaster = getRaster(inputProduct.getBand(L1FLAGS_INPUT_BAND_NAME), rectangle);
            Raster l1flagsTargetRaster = targetRasters.get(targetProduct.getBand(L1FLAGS_INPUT_BAND_NAME));
            // TODO replace copy by OpImage delegation
            final int length = rectangle.width * rectangle.height;
            System.arraycopy(l1flagsSourceRaster.getDataBuffer().getElems(), 0, l1flagsTargetRaster.getDataBuffer().getElems(), 0, length);
            pm.worked(1);

            Raster lowerRaster = getRaster(_lowerInputBand, rectangle);
            Raster upperRaster = getRaster(_upperInputBand, rectangle);

            Raster ndvi = targetRasters.get(targetProduct.getBand(NDVI_BAND_NAME));
            Raster ndviFlags = targetRasters.get(targetProduct.getBand(NDVI_FLAGS_BAND_NAME));

            float ndviValue;
            int ndviFlagsValue;

            for (int y = rectangle.y; y < rectangle.y + rectangle.height; y++) {
                for (int x = rectangle.x; x < rectangle.x + rectangle.width; x++) {
                    final float upper = upperRaster.getFloat(x, y);
                    final float lower = lowerRaster.getFloat(x, y);
                    ndviValue = (upper - lower) / (upper + lower);
                    ndviFlagsValue = 0;
                    if (Float.isNaN(ndviValue) || Float.isInfinite(ndviValue)) {
                        ndviFlagsValue |= NDVI_ARITHMETIC_FLAG_VALUE;
                        ndviValue = 0f;
                    }
                    if (ndviValue < 0.0f) {
                        ndviFlagsValue |= NDVI_LOW_FLAG_VALUE;
                    }
                    if (ndviValue > 1.0f) {
                        ndviFlagsValue |= NDVI_HIGH_FLAG_VALUE;
                    }
                    ndvi.setFloat(x, y, ndviValue);
                    ndviFlags.setInt(x, y, ndviFlagsValue);
                }
                pm.worked(1);
            }
        } finally {
            pm.done();
        }
    }

    private void loadSourceBands(Product product) throws OperatorException {
        _lowerInputBand = product.getBand(LOWER_INPUT_BAND_NAME);
        if (_lowerInputBand == null) {
            throw new OperatorException("Can not load band " + LOWER_INPUT_BAND_NAME);
        }

        _upperInputBand = product.getBand(UPPER_INPUT_BAND_NAME);
        if (_upperInputBand == null) {
            throw new OperatorException("Can not load band " + UPPER_INPUT_BAND_NAME);
        }

    }

    private static FlagCoding createNdviFlagCoding() {

        FlagCoding ndviFlagCoding = new FlagCoding("ndvi_flags");
        ndviFlagCoding.setDescription("NDVI Flag Coding");

        MetadataAttribute attribute;

        attribute = new MetadataAttribute(NDVI_ARITHMETIC_FLAG_NAME, ProductData.TYPE_INT32);
        attribute.getData().setElemInt(NDVI_ARITHMETIC_FLAG_VALUE);
        attribute.setDescription("NDVI value calculation failed due to an arithmetic exception");
        ndviFlagCoding.addAttribute(attribute);

        attribute = new MetadataAttribute(NDVI_LOW_FLAG_NAME, ProductData.TYPE_INT32);
        attribute.getData().setElemInt(NDVI_LOW_FLAG_VALUE);
        attribute.setDescription("NDVI value is too low");
        ndviFlagCoding.addAttribute(attribute);

        attribute = new MetadataAttribute(NDVI_HIGH_FLAG_NAME, ProductData.TYPE_INT32);
        attribute.getData().setElemInt(NDVI_HIGH_FLAG_VALUE);
        attribute.setDescription("NDVI value is too high");
        ndviFlagCoding.addAttribute(attribute);

        return ndviFlagCoding;
    }

    public static class Spi extends AbstractOperatorSpi {

        public Spi() {
            super(NdviOp.class, "SimpleNdvi");
        }

    }
}