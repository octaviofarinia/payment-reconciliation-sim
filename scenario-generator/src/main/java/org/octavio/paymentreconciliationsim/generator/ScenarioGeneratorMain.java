package org.octavio.paymentreconciliationsim.generator;
/** Pure process adapter; all CLI decisions live in the covered GeneratorCli. */
public final class ScenarioGeneratorMain {
 public static void main(String[] args){System.exit(GeneratorCli.run(args,System.getenv(),System.out,System.err));}
}
