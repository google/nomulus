// Copyright 2019 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.


package google.registry.monitoring.blackbox.messages;

import com.google.common.collect.ImmutableList;
import google.registry.monitoring.blackbox.exceptions.FailureException;
import io.netty.buffer.ByteBuf;
import javax.inject.Inject;

public abstract class EppResponseMessage extends EppMessage implements InboundMessageType{
  protected String expectedClTRID;
  protected String expectedDomainName;

  public abstract void getDocument(String clTRID, ByteBuf buf) throws FailureException;

  protected void getDocument(ByteBuf buf) throws FailureException {

    int capacity = buf.readInt() - 4;
    byte[] response = new byte[capacity];

    buf.readBytes(response);
    message = byteArrayToXmlDoc(response);
  }

  public abstract void decode() throws FailureException;

  public void updateInformation(String expectedClTRID, String expectedDomainName) {
    this.expectedClTRID = expectedClTRID;
    this.expectedDomainName = expectedDomainName;
  }



  public static class SimpleSuccess extends EppResponseMessage {
    @Inject
    public SimpleSuccess() {}

    @Override
    public void getDocument(String clTRID, ByteBuf buf) throws FailureException {
      this.expectedClTRID = clTRID;
      super.getDocument(buf);
    }

    @Override
    public void decode() throws FailureException{
      verifyEppResponse(
          message,
          ImmutableList.of(
              String.format("//eppns:clTRID[.='%s']", expectedClTRID),
            XPASS_EXPRESSION),
          true);
    }
  }
  public static class DomainExists extends EppResponseMessage {

    @Inject
    public DomainExists() {}

    @Override
    public void getDocument(String clTRID, ByteBuf buf) throws FailureException {
      this.expectedClTRID = clTRID;
      super.getDocument(buf);
    }

    @Override
    public void decode() throws FailureException {
      verifyEppResponse(
          message,
          ImmutableList.of(
              String.format("//eppns:clTRID[.='%s']", expectedClTRID),
              String.format("//domainns:name[@avail='false'][.='%s']", expectedDomainName),
              XPASS_EXPRESSION),
          true);
    }
  }
  public static class DomainNotExists extends EppResponseMessage {

    @Inject
    public DomainNotExists() {}

    @Override
    public void getDocument(String clTRID, ByteBuf buf) throws FailureException {
      this.expectedClTRID = clTRID;
      super.getDocument(buf);
    }

    @Override
    public void decode() throws FailureException {
      verifyEppResponse(
          message,
          ImmutableList.of(
              String.format("//eppns:clTRID[.='%s']", expectedClTRID),
              String.format("//domainns:name[@avail='true'][.='%s']", expectedDomainName),
              XPASS_EXPRESSION),
          true);
    }
  }

  public static class Failure extends EppResponseMessage {
    @Inject
    public Failure() {}

    public void getDocument(String clTRID, ByteBuf buf) throws FailureException {
      this.expectedClTRID = clTRID;
      super.getDocument(buf);
    }

    @Override
    public void decode() throws FailureException {
      verifyEppResponse(
          message,
          ImmutableList.of(
              String.format("//eppns:clTRID[.='%s']", expectedClTRID),
              XFAIL_EXPRESSION),
          true);
    }
  }
  public static class Greeting extends EppResponseMessage {
    @Inject
    public Greeting() {}

    @Override
    public void getDocument(String clTRID, ByteBuf buf) throws FailureException {
      super.getDocument(buf);
    }

    @Override
    public void decode() throws FailureException {
      verifyEppResponse(
          message,
          ImmutableList.of("//eppns:greeting"),
          true);
    }

  }
}
