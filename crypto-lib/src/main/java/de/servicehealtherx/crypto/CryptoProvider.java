package de.servicehealtherx.crypto;

import de.servicehealtherx.crypto.model.CryptoOperationRequest;
import de.servicehealtherx.crypto.model.CryptoOperationResult;

import java.util.List;
import java.util.Map;

public interface CryptoProvider {

    CryptoOperationResult sign(CryptoOperationRequest request);

    boolean verify(CryptoOperationRequest request, byte[] signature);

    CryptoOperationResult encrypt(CryptoOperationRequest request);

    CryptoOperationResult decrypt(CryptoOperationRequest request);

    List<KeyStoreDescriptor> listKeyStores();

    KeyStoreAvailability getAvailability(KeyAlias alias);

    Map<String, KeyStoreAvailability> getAvailabilities();
}
