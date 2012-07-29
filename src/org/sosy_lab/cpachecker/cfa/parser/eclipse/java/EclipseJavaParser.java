/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2012  Dirk Beyer
 *  All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 *  CPAchecker web page:
 *    http://cpachecker.sosy-lab.org
 */
package org.sosy_lab.cpachecker.cfa.parser.eclipse.java;

import java.io.File;
import java.io.IOException;
import java.util.Hashtable;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.eclipse.cdt.core.parser.IScannerInfo;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.sosy_lab.common.LogManager;
import org.sosy_lab.common.Timer;
import org.sosy_lab.cpachecker.cfa.CParser;
import org.sosy_lab.cpachecker.cfa.ParseResult;
import org.sosy_lab.cpachecker.cfa.parser.eclipse.CFAGenerationRuntimeException;
import org.sosy_lab.cpachecker.exceptions.ParserException;

/**
 * Base implementation that should work with all CDT versions we support.
 */
public  class EclipseJavaParser implements CParser {

  private static final int START_OF_STRING = 0;


  private boolean ignoreCasts;


  private final LogManager logger;

  private final Timer parseTimer = new Timer();
  private final Timer cfaTimer = new Timer();

  public EclipseJavaParser(LogManager pLogger) {
    logger = pLogger;


  }



  @Override
  public ParseResult parseFile(String pFilename) throws ParserException, IOException {

    return buildCFA(parse(pFilename));
  }

  @Override
  public ParseResult parseString(String pCode) throws ParserException {

    return buildCFA(parse((pCode)));
  }

  @Override
  public org.sosy_lab.cpachecker.cfa.ast.c.CAstNode parseSingleStatement(String pCode) throws ParserException {
      throw new ParserException("Not Implemented");
  }



  @SuppressWarnings("unchecked")
  private CompilationUnit parse(final String pFileName) throws ParserException {
    parseTimer.start();
    try {

      File file = new File(pFileName);
      String source = FileUtils.readFileToString(file);








      //TODO Check if Windows use \

      final String[] sourceFilePath = {pFileName.substring( START_OF_STRING , pFileName.lastIndexOf("/")) };



      final String[] encoding = {"utf8"};


      ASTParser parser = ASTParser.newParser(AST.JLS4);






      parser.setSource(source.toCharArray());
      parser.setEnvironment( null, sourceFilePath, encoding, true);
      parser.setResolveBindings(true);
      parser.setStatementsRecovery(true);
      parser.setBindingsRecovery(true);

      // Set Compliance Options to support JDK 1.7
      Hashtable<String , String> options = JavaCore.getOptions();
      JavaCore.setComplianceOptions(JavaCore.VERSION_1_7, options);
      parser.setCompilerOptions(options);

      parser.setUnitName(pFileName.substring(pFileName.lastIndexOf("/") + 1));
      CompilationUnit unit = (CompilationUnit)parser.createAST(null);
      return unit;

    } catch (CFAGenerationRuntimeException e) {
      throw new ParserException(e);
    } catch (IOException e) {
      throw new ParserException(e);
    } finally {
      parseTimer.stop();
    }
  }


  private ParseResult buildCFA(CompilationUnit ast) throws ParserException {
    cfaTimer.start();
    try {
      AstErrorChecker checker = new AstErrorChecker(logger);
      CFABuilder builder = new CFABuilder(logger, ignoreCasts);
      try {
        ast.accept(checker);
        ast.accept(builder);
      } catch (CFAGenerationRuntimeException e) {
        throw new ParserException(e);
      }

      return new ParseResult(builder.getCFAs(), builder.getCFANodes(), builder.getGlobalDeclarations());

    } finally {
      cfaTimer.stop();
    }
  }


  @Override
  public Timer getParseTime() {
    return parseTimer;
  }

  @Override
  public Timer getCFAConstructionTime() {
    return cfaTimer;
  }

  /**
   * Private class that tells the Eclipse CDT scanner that no macros and include
   * paths have been defined externally.
   */
  protected static class StubScannerInfo implements IScannerInfo {

    protected final static IScannerInfo instance = new StubScannerInfo();

    @Override
    public Map<String, String> getDefinedSymbols() {
      // the externally defined pre-processor macros
      return null;
    }

    @Override
    public String[] getIncludePaths() {
      return new String[0];
    }
  }

  public void setIgnoreCasts(boolean pIgnoreCasts) {
    ignoreCasts = pIgnoreCasts;
  }
}