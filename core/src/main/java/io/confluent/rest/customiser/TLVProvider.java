package io.confluent.rest.customiser;

public interface TLVProvider {
  byte[] getTLV(int type);
}
