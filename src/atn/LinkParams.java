/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package atn;

/**
 *
 * @author justinacotter
 */
public final class LinkParams {
    
        private double paramA;
        private double paramB0;
        private double paramD;
        private double paramEPlant;
        private double paramEAnimal;
        private double paramQ;
        private double paramY;
        
        public LinkParams() {
            resetParamA();
            resetParamB0();
            resetParamD();
            resetParamEPlant();
            resetParamEAnimal();
            resetParamQ();
            resetParamY();
        }
        
        public void setParamA (double a) {
            paramA = a;
        }

        public void setParamB0 (double b0) {
            paramB0 = b0;
        }

        public void setParamD (double d) {
            paramD = d;
        }

        public void setParamEPlant (double e) {
            paramEPlant = e;
        }

        public void setParamEAnimal (double e) {
            paramEAnimal = e;
        }

        public void setParamQ (double q) {
            paramQ = q;
        }

        public void setParamY (double y) {
            paramY = y;
        }

        public double getParamA () {
            return paramA;
        }

        public double getParamB0 () {
            return paramB0;
        }

        public double getParamD () {
            return paramD;
        }

        public double getParamEPlant () {
            return paramEPlant;
        }

        public double getParamEAnimal () {
            return paramEAnimal;
        }

        public double getParamQ () {
            return paramQ;
        }

        public double getParamY () {
            return paramY;
        }

        public void resetParamA () {
            paramA = Double.valueOf(ATN.propertiesConfig.
                getProperty("relativeHalfSaturationDensityDefault"));
        }

        public void resetParamB0 () {
            paramB0 = Double.valueOf(ATN.propertiesConfig.
                getProperty("halfSaturationDensityDefault"));
        }

        public void resetParamD () {
            paramD = Double.valueOf(ATN.propertiesConfig.
                getProperty("predatorInterferenceDefault"));
        }

        public void resetParamEPlant () {
            paramEPlant = Double.valueOf(ATN.propertiesConfig.
                getProperty("assimilationEfficiencyPlantDefault"));
        }

        public void resetParamEAnimal () {
            paramEAnimal = Double.valueOf(ATN.propertiesConfig.
                getProperty("assimilationEfficiencyAnimalDefault"));
        }

        public void resetParamQ () {
            paramQ = Double.valueOf(ATN.propertiesConfig.
                getProperty("functionalResponseControlParameterDefault"));
        }

        public void resetParamY () {
            paramY = Double.valueOf(
                ATN.propertiesConfig.
                getProperty("maximumIngestionRateDefault"));
        }

}
