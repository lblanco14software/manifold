package manifold.internal.javac;

import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.tools.javac.api.BasicJavacTask;
import com.sun.tools.javac.api.ClientCodeWrapper;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.JCDiagnostic;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Names;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import manifold.api.type.DisableStringLiteralTemplates;

public class StringLiteralTemplateProcessor extends TreeTranslator implements TaskListener
{
  private final BasicJavacTask _javacTask;
  private final TreeMaker _maker;
  private final Names _names;

  public static void register( JavacTask task )
  {
    task.addTaskListener( new StringLiteralTemplateProcessor( task ) );
  }

  private StringLiteralTemplateProcessor( JavacTask task )
  {
    _javacTask = (BasicJavacTask)task;
    _maker = TreeMaker.instance( _javacTask.getContext() );
    _names = Names.instance( _javacTask.getContext() );
  }

  @Override
  public void started( TaskEvent taskEvent )
  {
    // nothing to do
  }

  @Override
  public void finished( TaskEvent e )
  {
    if( e.getKind() != TaskEvent.Kind.PARSE )
    {
      return;
    }

    for( Tree tree : e.getCompilationUnit().getTypeDecls() )
    {
      if( !(tree instanceof JCTree.JCClassDecl) )
      {
        continue;
      }
      
      JCTree.JCClassDecl classDecl = (JCTree.JCClassDecl)tree;
      for( JCTree.JCAnnotation anno: classDecl.getModifiers().getAnnotations() )
      {
        if( anno.annotationType.toString().contains( DisableStringLiteralTemplates.class.getSimpleName() ) )
        {
          return;
        }
      }
      classDecl.accept( this );
    }
  }

  @Override
  public void visitLiteral( JCTree.JCLiteral jcLiteral )
  {
    super.visitLiteral( jcLiteral );

    Object value = jcLiteral.getValue();
    if( !(value instanceof String) )
    {
      return;
    }

    String stringValue = (String)value;
    List<JCTree.JCExpression> exprs = new TemplateParser( stringValue ).parse( jcLiteral.getPreferredPosition() );
    JCTree.JCBinary concat = null;
    while( !exprs.isEmpty() )
    {
      if( concat == null )
      {
        concat = _maker.Binary( JCTree.Tag.PLUS, exprs.remove( 0 ), exprs.remove( 0 ) );
      }
      else
      {
        concat = _maker.Binary( JCTree.Tag.PLUS, concat, exprs.remove( 0 ) );
      }
    }

    result = concat == null ? result : concat;
  }

  class TemplateParser
  {
    private String _stringValue;
    private int _index;
    private StringBuilder _contentExpr;

    TemplateParser( String stringValue )
    {
      _stringValue = stringValue;
    }

    public List<JCTree.JCExpression> parse( int literalOffset )
    {
      List<Expr> comps = split();
      if( comps.isEmpty() )
      {
        return Collections.emptyList();
      }

      List<JCTree.JCExpression> exprs = new ArrayList<>();
      Expr prev = null;
      for( Expr comp: comps )
      {
        JCTree.JCExpression expr;
        if( comp.isVerbatim() )
        {
          expr = _maker.Literal( comp._expr );
        }
        else
        {
          if( prev != null && !prev.isVerbatim() )
          {
            // force concatenation
            exprs.add( _maker.Literal( "" ) );
          }

          int exprPos = literalOffset + 1 + comp._offset;

          if( comp.isIdentifier() )
          {
             JCTree.JCIdent ident = _maker.Ident( _names.fromString( comp.getExpr() ) );
             ident.pos = exprPos;
             expr = ident;
          }
          else
          {
            DiagnosticCollector<JavaFileObject> errorHandler = new DiagnosticCollector<>();
            expr = JavaParser.instance().parseExpr( comp._expr, errorHandler );
            if( transferParseErrors( literalOffset, comp, expr, errorHandler ) )
            {
              return Collections.emptyList();
            }
            replaceNames( expr, exprPos );
          }
        }
        prev = comp;
        exprs.add( expr );
      }

      if( exprs.size() == 1 )
      {
        // insert an empty string so concat will make the expr a string
        exprs.add( 0, _maker.Literal( "" ) );
      }

      return exprs;
    }

    private boolean transferParseErrors( int literalOffset, Expr comp, JCTree.JCExpression expr, DiagnosticCollector<JavaFileObject> errorHandler )
    {
      if( expr == null || errorHandler.getDiagnostics().stream().anyMatch( e -> e.getKind() == Diagnostic.Kind.ERROR ) )
      {
        //## todo: add errors reported in the expr
        //Log.instance( _javacTask.getContext() ).error( new JCDiagnostic.SimpleDiagnosticPosition( literalOffset + 1 + comp._offset ),  );
        for( Diagnostic<? extends JavaFileObject> diag: errorHandler.getDiagnostics() )
        {
          if( diag.getKind() == Diagnostic.Kind.ERROR )
          {
            JCDiagnostic jcDiag = ((ClientCodeWrapper.DiagnosticSourceUnwrapper)diag).d;
//                JCDiagnostic.Factory.instance( _javacTask.getContext() ).error(
//                  Log.instance( _javacTask.getContext() ).currentSource(),
//                  new JCDiagnostic.SimpleDiagnosticPosition( literalOffset + 1 + comp._offset ), diag.getCode(), jcDiag.getArgs() );
            Log.instance( _javacTask.getContext() ).error( new JCDiagnostic.SimpleDiagnosticPosition( literalOffset + 1 + comp._offset ), diag.getCode(), jcDiag.getArgs() );
          }
        }
        return true;
      }
      return false;
    }

    private void replaceNames( JCTree.JCExpression expr, int offset )
    {
      expr.accept( new NameReplacer( _javacTask, offset ) );
    }

    private List<Expr> split()
    {
      List<Expr> comps = new ArrayList<>();
      _contentExpr = new StringBuilder();
      int length = _stringValue.length();
      int offset = 0;
      for( _index = 0; _index < length; _index++ )
      {
        char c = _stringValue.charAt( _index );
        if( c == '$' )
        {
          Expr expr = parseExpr();
          if( expr != null )
          {
            if( _contentExpr.length() > 0 )
            {
              // add
              comps.add( new Expr( _contentExpr.toString(), offset, ExprKind.Verbatim ) );
              _contentExpr = new StringBuilder();
              offset = _index+1;
            }
            comps.add( expr );
            continue;
          }
        }
        _contentExpr.append( c );
      }

      if( !comps.isEmpty() && _contentExpr.length() > 0 )
      {
        comps.add( new Expr( _contentExpr.toString(), offset, ExprKind.Verbatim ) );
      }

      return comps;
    }

    private Expr parseExpr()
    {
      if( _index + 1 == _stringValue.length() )
      {
        return null;
      }

      return _stringValue.charAt( _index + 1 ) == '{'
             ? parseBraceExpr()
             : parseSimpleExpr();
    }

    private Expr parseBraceExpr()
    {
      int length = _stringValue.length();
      StringBuilder expr = new StringBuilder();
      int index = _index+2;
      int offset = index;
      for( ; index < length; index++ )
      {
        char c = _stringValue.charAt( index );
        if( c != '}' )
        {
          expr.append( c );
        }
        else
        {
          if( expr.length() > 0  )
          {
            _index = index;
            return new Expr( expr.toString(), offset, ExprKind.Complex );
          }
          break;
        }
      }
      return null;
    }

    private Expr parseSimpleExpr()
    {
      int length = _stringValue.length();
      int index = _index+1;
      int offset = index;
      StringBuilder expr = new StringBuilder();
      for( ; index < length; index++ )
      {
        char c = _stringValue.charAt( index );
        if( expr.length() == 0 )
        {
          if( c != '$' && Character.isJavaIdentifierStart( c ) )
          {
            expr.append( c );
          }
          else
          {
            return null;
          }
        }
        else if( c != '$' && Character.isJavaIdentifierPart( c ) )
        {
          expr.append( c );
        }
        else
        {
          break;
        }
        _index = index;
      }
      return expr.length() > 0 ? new Expr( expr.toString(), offset, ExprKind.Identifier ) : null;
    }

    class Expr
    {
      private String _expr;
      private ExprKind _kind;
      private int _offset;

      Expr( String expr, int offset, ExprKind kind )
      {
        _expr = expr;
        _offset = offset;
        _kind = kind;
      }

      String getExpr()
      {
        return _expr;
      }

      int getOffset()
      {
        return _offset;
      }

      boolean isVerbatim()
      {
        return _kind == ExprKind.Verbatim;
      }

      boolean isIdentifier()
      {
        return _kind == ExprKind.Identifier;
      }
    }
  }

  enum ExprKind
  {
    Verbatim,
    Identifier,
    Complex
  }
}
