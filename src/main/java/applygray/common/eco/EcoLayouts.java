package applygray.common.eco;

/** Native +Y row order. Rack geometry reference: GTQT/PrismPlan (8dbed09, GPL-3.0). */
final class EcoLayouts {
    static final String[] RACK_CAP = {"XX", "XX", "XX"};
    static final String[] RACK_CONTROLLER = {"NX", "SX", "XX"};
    static final String[] COMPUTER_SECTION = {"TP", "UF", "TP"};
    static final String[] STORAGE_SECTION = {"TJ", "TX", "TJ"};
    static final String[] RACK_REAR = {"HH", "HH", "HH"};
    static final String[] HALL_FRONT = {"CCCCCCC", "FCCSCCF", "FCCNCCF", "FCCCCCF", "FFFFFFF"};
    static final String[] HALL_WORKER = {"CCCCCCC", "CCCCCCC", "G  W  G", "G     G", "FHHHHHF"};
    static final String[] HALL_BUS = {"CCCCCCC", "BCCCCCC", "G     G", "G     G", "FHHHHHF"};
    static final String[] HALL_SERVICE = {"CCCCCCC", "CCCCCCC", "G     G", "G     G", "FHHHHHF"};
    static final String[] HALL_REAR = {"CCCCCCC", "FCCCCCF", "FCCCCCF", "FCCCCCF", "FFFFFFF"};
    private EcoLayouts() {}
}
