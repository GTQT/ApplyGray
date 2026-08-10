package applygray.mattermanipulator.integration.ae2;

import java.util.Optional;

import ae2.helpers.patternprovider.PatternProviderLogic;

/** Mixin-backed Smart Copy state exposed by an AE2 Pattern Provider part. */
public interface SmartCopyPatternProviderLinkable {

    Optional<SmartCopyPatternProviderLink> applygray$getSmartCopyLink();

    boolean applygray$setSmartCopyLink(SmartCopyPatternProviderLink source);

    void applygray$clearSmartCopyLink();

    PatternProviderLogic applygray$getSmartCopySourceLogic();

    void applygray$refreshSmartCopySource();

    void applygray$invalidateSmartCopySource();
}
