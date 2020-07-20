<?xml version="1.0"?>
<p:declare-step version="1.0" xmlns:p="http://www.w3.org/ns/xproc">
  <p:input port="source"/>
  <p:output port="result"/>
  <p:validate-with-relax-ng>
    <p:input port="schema">
      <p:document href="datamodel.rng"/>
    </p:input>
  </p:validate-with-relax-ng>
</p:declare-step>
