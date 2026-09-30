import 'dart:io';

import 'package:analyzer/dart/analysis/analysis_context_collection.dart';
import 'package:analyzer/dart/analysis/results.dart';
import 'package:analyzer/dart/analysis/utilities.dart';
import 'package:analyzer/dart/ast/ast.dart';
import 'package:analyzer/dart/element/element.dart';
import 'package:analyzer/dart/element/type.dart';
import 'package:analyzer/source/line_info.dart';
import 'package:path/path.dart' as p;

const protocolVersion = 1;
const exporterVersion = '0.3.0';
const analyzerVersion = '8.4.1';
const supportedSdkVersion = '3.9.2';

/// The root fixes file identities even when exporting just one package file.
Stream<Map<String, Object?>> exportProject({
  required String root,
  String? input,
  String? sdkPath,
}) async* {
  root = p.normalize(p.absolute(root));
  input = p.normalize(p.absolute(input ?? root));
  if (!Directory(root).existsSync() ||
      !(input == root || p.isWithin(root, input))) {
    throw ArgumentError('Input must be within an existing project root');
  }
  final type = FileSystemEntity.typeSync(input);
  if (type != FileSystemEntityType.file &&
      type != FileSystemEntityType.directory) {
    throw ArgumentError('Input does not exist: $input');
  }
  if (type == FileSystemEntityType.file && !input.endsWith('.dart')) {
    throw ArgumentError('Input file must have a .dart extension');
  }
  sdkPath = p.normalize(
    p.absolute(sdkPath ?? p.dirname(p.dirname(Platform.resolvedExecutable))),
  );
  final sdkVersionFile = File(p.join(sdkPath, 'version'));
  if (!sdkVersionFile.existsSync() ||
      !Directory(p.join(sdkPath, 'lib')).existsSync()) {
    throw ArgumentError('Not a Dart SDK: $sdkPath');
  }
  final sdkVersion = sdkVersionFile.readAsStringSync().trim();
  if (sdkVersion != supportedSdkVersion) {
    throw ArgumentError(
      'Expected Dart SDK $supportedSdkVersion, found $sdkVersion',
    );
  }
  Iterable<File> sourceFiles(Directory directory) sync* {
    for (final entry in directory.listSync(followLinks: false)) {
      if (entry is Directory) {
        if (!['.git', '.dart_tool'].contains(p.basename(entry.path))) {
          yield* sourceFiles(entry);
        }
      } else if (entry is File && entry.path.endsWith('.dart')) {
        yield entry;
      }
    }
  }

  final files = type == FileSystemEntityType.file
      ? [input]
      : sourceFiles(Directory(input)).map((file) => file.path).toList();
  files.sort();
  final collection = AnalysisContextCollection(
    includedPaths: [root],
    sdkPath: sdkPath,
  );
  try {
    yield {
      'record': 'header',
      'protocolVersion': protocolVersion,
      'exporterVersion': exporterVersion,
      'analyzerVersion': analyzerVersion,
      'sdkVersion': sdkVersion,
      'offsetEncoding': 'utf-16',
      'conditionalEnvironment': 'analyzer-default',
    };
    for (final file in files) {
      Object? result;
      String? content;
      try {
        content = File(file).readAsStringSync();
        final context = collection.contextFor(file);
        result = await context.currentSession.getResolvedUnit(file);
      } on Exception catch (error) {
        result = error;
      } on StateError catch (error) {
        result = error;
      }
      if (result is ResolvedUnitResult) {
        yield _UnitEncoder(root, file, result.content, result.lineInfo).encode(
          result.unit,
          result.diagnostics
              .map(
                (error) => <String, Object?>{
                  'code': error.diagnosticCode.name,
                  'severity': error.diagnosticCode.severity.name,
                  'message': error.message,
                  'offset': error.offset,
                  'length': error.length,
                },
              )
              .toList(),
          library: result.libraryElement.firstFragment.source.fullName,
        );
      } else {
        final parsed = parseString(
          content: content ?? '',
          path: file,
          throwIfDiagnostics: false,
        );
        yield _UnitEncoder(root, file, parsed.content, parsed.lineInfo).encode(
          parsed.unit,
          [
            {
              'code': 'resolution_unavailable',
              'message': 'Resolution unavailable: $result',
              'severity': 'ERROR',
            },
            ...parsed.errors.map(
              (error) => <String, Object?>{
                'code': error.diagnosticCode.name,
                'severity': error.diagnosticCode.severity.name,
                'message': error.message,
                'offset': error.offset,
                'length': error.length,
              },
            ),
          ],
          resolved: false,
        );
      }
    }
    yield {'record': 'summary', 'files': files.length};
  } finally {
    await collection.dispose();
  }
}

class _UnitEncoder {
  final String root;
  final String file;
  final String source;
  final LineInfo lines;
  final Map<String, Map<String, Object?>> symbols = {};
  final List<Map<String, Object?>> nodes = [];
  final Set<String> unsupported = {};

  _UnitEncoder(this.root, this.file, this.source, this.lines);

  String fileId(String path) => p.isWithin(root, path)
      ? Uri(
          path: p.posix.joinAll(p.split(p.relative(path, from: root))),
        ).toString()
      : Uri.file(path).toString();

  String? typeId(DartType? type) =>
      type is InterfaceType ? symbol(type.element) : type?.getDisplayString();

  String? symbol(Element? element) {
    if (element == null) return null;
    while (element is PatternVariableElement && element.join != null) {
      element = element.join!;
    }
    element = element!.baseElement;
    final fragment = element.firstFragment;
    final unit = fragment.libraryFragment;
    final uri = unit?.source.uri;
    final location = uri != null && uri.scheme != 'file'
        ? uri.toString()
        : unit == null
        ? ''
        : fileId(unit.source.fullName);
    // Function-type parameters can have neither a name offset nor a usable
    // canonical offset in analyzer 8.4.1. Anchor them to their owner and slot.
    final offset =
        fragment.nameOffset ??
        (fragment is ConstructorFragment ||
                fragment is LocalFunctionFragment ||
                fragment is ExtensionFragment
            ? fragment.offset
            : element.enclosingElement?.firstFragment.nameOffset ?? -1);
    final owner = element.enclosingElement;
    final slot =
        element is FormalParameterElement && fragment.nameOffset == null
        ? ':${owner is FunctionTypedElement ? owner.formalParameters.indexOf(element) : -1}:${element.type.getDisplayString()}'
        : '';
    final id = '$location#$offset:${element.kind.name}:${element.name}$slot';
    if (!symbols.containsKey(id)) {
      symbols[id] = {
        'id': id,
        'name': element.name,
        'kind': element.kind.name,
        'private': element.isPrivate,
        'synthetic': element.isSynthetic,
        if (element is ExecutableElement) 'static': element.isStatic,
        if (element is PropertyAccessorElement)
          'variable': symbol(element.variable),
        if (element is InterfaceElement) ...{
          'type': element.thisType.getDisplayString(),
          'superTypes': element.allSupertypes
              .map((t) => t.getDisplayString())
              .toList(),
        },
        'file': location,
        'offset': offset,
        'library': element.library == null
            ? null
            : fileId(element.library!.firstFragment.source.fullName),
        if (element is VariableElement) ...{
          'type': element.type.getDisplayString(),
          'static': element.isStatic,
          'final': element.isFinal || element.isConst,
        },
        if (element is ExecutableElement)
          'returnType': element.returnType.getDisplayString(),
        if (element is FormalParameterElement) ...{
          'named': element.isNamed,
          'required': element.isRequired,
          'defaultValue': element.defaultValueCode,
        },
      };
      if (element is VariableElement) {
        symbols[id]!['typeId'] = typeId(element.type);
      }
      if (element is ExecutableElement) {
        symbols[id]!['returnTypeId'] = typeId(element.returnType);
      }
      if (element is ExtensionElement) {
        symbols[id]!['extendedType'] = typeId(element.extendedType);
      }
      if (element is InterfaceElement) {
        symbols[id]!['superDeclarations'] = element.allSupertypes
            .map((t) => symbol(t.element))
            .toList();
      }
      if (element.enclosingElement is InterfaceElement ||
          element.enclosingElement is ExtensionElement) {
        symbols[id]!['owner'] = symbol(element.enclosingElement);
      }
      if (element is ConstructorElement) {
        symbols[id]!['factory'] = element.isFactory;
        symbols[id]!['superConstructor'] = symbol(element.superConstructor);
      }
      if (element is ExecutableElement) {
        symbols[id]!['parameters'] = element.formalParameters
            .map(symbol)
            .toList();
      }
    }
    return id;
  }

  Map<String, Object?> encode(
    CompilationUnit unit,
    List<Map<String, Object?>> diagnostics, {
    String? library,
    bool resolved = true,
  }) {
    node(unit);
    return {
      'record': 'unit',
      'file': fileId(file),
      'library': library == null ? null : fileId(library),
      'status': !resolved
          ? 'parsed'
          : diagnostics.any((d) => d['severity'] == 'ERROR')
          ? 'partial'
          : 'resolved',
      'source': source,
      'languageVersion': unit.languageVersion.effective.toString(),
      'diagnostics': diagnostics,
      'unsupportedKinds': unsupported.toList()..sort(),
      'nodes': nodes,
      'symbols': (symbols.keys.toList()..sort())
          .map((id) => symbols[id])
          .toList(),
    };
  }

  int node(AstNode ast) {
    final id = nodes.length;
    final location = lines.getLocation(ast.offset);
    final record = <String, Object?>{
      'id': id,
      'offset': ast.offset,
      'length': ast.length,
      'line': location.lineNumber,
      'column': location.columnNumber,
    };
    nodes.add(record);
    final children = <Map<String, Object?>>[];
    void child(String role, AstNode? value) {
      if (value != null) children.add({'role': role, 'node': node(value)});
    }

    void many(String role, Iterable<AstNode> values) {
      for (final value in values) {
        child(role, value);
      }
    }

    String kind;
    switch (ast) {
      case CompilationUnit():
        kind = 'CompilationUnit';
        many('directive', ast.directives);
        many('declaration', ast.declarations);
      case LibraryDirective():
        kind = 'LibraryDirective';
      case ExportDirective():
        kind = 'ExportDirective';
        final uri = ast.libraryExport?.uri;
        if (uri is DirectiveUriWithRelativeUriString) {
          record['selectedUri'] = uri.relativeUriString;
        }
        child('uri', ast.uri);
        many('combinator', ast.combinators);
        many('configuration', ast.configurations);
      case PartDirective():
        kind = 'PartDirective';
        child('uri', ast.uri);
      case PartOfDirective():
        kind = 'PartOfDirective';
        child('uri', ast.uri);
      case ShowCombinator():
        kind = 'ShowCombinator';
        record['names'] = ast.shownNames.map((n) => n.name).toList();
      case HideCombinator():
        kind = 'HideCombinator';
        record['names'] = ast.hiddenNames.map((n) => n.name).toList();
      case ImportDirective():
        kind = 'ImportDirective';
        final uri = ast.libraryImport?.uri;
        if (uri is DirectiveUriWithRelativeUriString) {
          record['selectedUri'] = uri.relativeUriString;
        }
        child('uri', ast.uri);
        child('prefix', ast.prefix);
        many('configuration', ast.configurations);
        many('combinator', ast.combinators);
      case Configuration():
        kind = 'Configuration';
        record['name'] = ast.name.toSource();
        child('uri', ast.uri);
        child('value', ast.value);
      case MixinDeclaration():
        kind = 'MixinDeclaration';
        record['name'] = ast.name.lexeme;
        record['declaration'] = symbol(ast.declaredFragment?.element);
        record['modifiers'] = ['mixin', if (ast.baseKeyword != null) 'base'];
        child('typeParameters', ast.typeParameters);
        many('member', ast.members);
      case ExtensionDeclaration():
        kind = 'ExtensionDeclaration';
        record['name'] = ast.name?.lexeme ?? '<extension>@${ast.offset}';
        record['declaration'] = symbol(ast.declaredFragment?.element);
        child('typeParameters', ast.typeParameters);
        child('onType', ast.onClause?.extendedType);
        many('member', ast.members);
      case ExtensionTypeDeclaration():
        kind = 'ExtensionTypeDeclaration';
        record['name'] = ast.name.lexeme;
        record['declaration'] = symbol(ast.declaredFragment?.element);
        child('typeParameters', ast.typeParameters);
        child('representation', ast.representation);
        many('member', ast.members);
      case RepresentationDeclaration():
        kind = 'RepresentationDeclaration';
        record['name'] = ast.fieldName.lexeme;
        record['declaration'] = symbol(ast.fieldFragment?.element);
        record['constructor'] = symbol(ast.constructorFragment?.element);
        child('type', ast.fieldType);
      case ClassDeclaration():
        kind = 'ClassDeclaration';
        record['modifiers'] = [
          if (ast.abstractKeyword != null) 'abstract',
          if (ast.baseKeyword != null) 'base',
          if (ast.finalKeyword != null) 'final',
          if (ast.interfaceKeyword != null) 'interface',
          if (ast.sealedKeyword != null) 'sealed',
          if (ast.mixinKeyword != null) 'mixin',
        ];
        record['implicitConstructor'] = ast
            .declaredFragment
            ?.element
            .constructors
            .where((c) => c.isSynthetic)
            .map(symbol)
            .firstOrNull;
        record['name'] = ast.name.lexeme;
        record['declaration'] = symbol(ast.declaredFragment?.element);
        child('typeParameters', ast.typeParameters);
        many('member', ast.members);
      case TypeParameterList():
        kind = 'TypeParameterList';
        many('parameter', ast.typeParameters);
      case TypeParameter():
        kind = 'TypeParameter';
        record['name'] = ast.name.lexeme;
        child('bound', ast.bound);
      case TopLevelVariableDeclaration():
        kind = 'TopLevelVariableDeclaration';
        child('variables', ast.variables);
      case FieldDeclaration():
        kind = 'FieldDeclaration';
        record['static'] = ast.isStatic;
        child('variables', ast.fields);
      case MethodDeclaration():
        kind = 'MethodDeclaration';
        record['name'] = ast.name.lexeme;
        record['declaration'] = symbol(ast.declaredFragment?.element);
        record['static'] = ast.isStatic;
        record['getter'] = ast.isGetter;
        record['setter'] = ast.isSetter;
        child('typeParameters', ast.typeParameters);
        child('parameters', ast.parameters);
        child('body', ast.body);
      case ConstructorDeclaration():
        kind = 'ConstructorDeclaration';
        record['name'] = ast.name?.lexeme ?? '<init>';
        record['declaration'] = symbol(ast.declaredFragment?.element);
        record['factory'] = ast.factoryKeyword != null;
        child('parameters', ast.parameters);
        many('initializer', ast.initializers);
        child('redirect', ast.redirectedConstructor);
        child('body', ast.body);
      case ConstructorFieldInitializer():
        kind = 'ConstructorFieldInitializer';
        child('field', ast.fieldName);
        child('expression', ast.expression);
      case SuperConstructorInvocation():
        kind = 'ConstructorInvocation';
        record['target'] = symbol(ast.element);
        record['name'] = ast.constructorName?.name ?? '<init>';
        child('arguments', ast.argumentList);
      case RedirectingConstructorInvocation():
        kind = 'ConstructorInvocation';
        record['target'] = symbol(ast.element);
        record['name'] = ast.constructorName?.name ?? '<init>';
        child('arguments', ast.argumentList);
      case ConstructorName():
        kind = 'ConstructorName';
        record['target'] = symbol(ast.element);
        record['name'] = ast.name?.name ?? '<init>';
      case FieldFormalParameter():
        kind = 'FieldFormalParameter';
        record['name'] = ast.name.lexeme;
        record['declaration'] = symbol(ast.declaredFragment?.element);
        record['field'] = symbol(ast.declaredFragment?.element.field);
      case SuperFormalParameter():
        kind = 'SuperFormalParameter';
        record['superParameter'] = symbol(
          ast.declaredFragment?.element.superConstructorParameter,
        );
        record['name'] = ast.name.lexeme;
        record['declaration'] = symbol(ast.declaredFragment?.element);
      case FunctionDeclarationStatement():
        kind = 'FunctionDeclarationStatement';
        child('function', ast.functionDeclaration);
      case FunctionDeclaration():
        kind = 'FunctionDeclaration';
        record['name'] = ast.name.lexeme;
        record['declaration'] = symbol(ast.declaredFragment?.element);
        child('returnType', ast.returnType);
        child('function', ast.functionExpression);
      case FunctionExpression():
        kind = 'FunctionExpression';
        record['declaration'] = symbol(ast.declaredFragment?.element);
        child('typeParameters', ast.typeParameters);
        child('parameters', ast.parameters);
        child('body', ast.body);
      case FormalParameterList():
        kind = 'FormalParameterList';
        many('parameter', ast.parameters);
      case DefaultFormalParameter():
        kind = 'DefaultFormalParameter';
        child('parameter', ast.parameter);
        child('defaultValue', ast.defaultValue);
      case FunctionTypedFormalParameter():
        kind = 'FunctionTypedFormalParameter';
        record['name'] = ast.name.lexeme;
        record['declaration'] = symbol(ast.declaredFragment?.element);
        child('parameters', ast.parameters);
        child('returnType', ast.returnType);
        child('typeParameters', ast.typeParameters);
      case GenericFunctionType():
        kind = 'GenericFunctionType';
        child('parameters', ast.parameters);
        child('returnType', ast.returnType);
        child('typeParameters', ast.typeParameters);
      case ExtensionOverride():
        kind = 'ExtensionOverride';
        record['reference'] = symbol(ast.element);
        child('arguments', ast.argumentList);
      case ConstructorReference():
        kind = 'ConstructorReference';
        record['target'] = symbol(ast.constructorName.element);
      case FunctionReference():
        kind = 'FunctionReference';
        child('expression', ast.function);
        child('typeArguments', ast.typeArguments);
      case SimpleFormalParameter():
        kind = 'SimpleFormalParameter';
        record['name'] = ast.name?.lexeme;
        record['declaration'] = symbol(ast.declaredFragment?.element);
        child('type', ast.type);
      case EmptyFunctionBody():
        kind = 'EmptyFunctionBody';
      case BlockFunctionBody():
        kind = 'BlockFunctionBody';
        child('block', ast.block);
      case ExpressionFunctionBody():
        kind = 'ExpressionFunctionBody';
        child('expression', ast.expression);
      case Block():
        kind = 'Block';
        many('statement', ast.statements);
      case VariableDeclarationStatement():
        kind = 'VariableDeclarationStatement';
        child('variables', ast.variables);
      case VariableDeclarationList():
        kind = 'VariableDeclarationList';
        child('type', ast.type);
        many('variable', ast.variables);
      case VariableDeclaration():
        kind = 'VariableDeclaration';
        record['name'] = ast.name.lexeme;
        record['declaration'] = symbol(ast.declaredFragment?.element);
        child('initializer', ast.initializer);
      case ExpressionStatement():
        kind = 'ExpressionStatement';
        child('expression', ast.expression);
      case ReturnStatement():
        kind = 'ReturnStatement';
        child('expression', ast.expression);
      case IfStatement():
        kind = 'IfStatement';
        child('condition', ast.expression);
        child('case', ast.caseClause);
        child('then', ast.thenStatement);
        child('else', ast.elseStatement);
      case WhileStatement():
        kind = 'WhileStatement';
        child('condition', ast.condition);
        child('body', ast.body);
      case DoStatement():
        kind = 'DoStatement';
        child('condition', ast.condition);
        child('body', ast.body);
      case ForStatement():
        kind = 'ForStatement';
        record['await'] = ast.awaitKeyword != null;
        child('parts', ast.forLoopParts);
        child('body', ast.body);
      case ForEachPartsWithPattern():
        kind = 'ForEachParts';
        child('pattern', ast.pattern);
        child('iterable', ast.iterable);
      case ForPartsWithPattern():
        kind = 'ForParts';
        child('init', ast.variables);
        child('condition', ast.condition);
        many('update', ast.updaters);
      case ForPartsWithDeclarations():
        kind = 'ForParts';
        child('init', ast.variables);
        child('condition', ast.condition);
        many('update', ast.updaters);
      case ForPartsWithExpression():
        kind = 'ForParts';
        child('init', ast.initialization);
        child('condition', ast.condition);
        many('update', ast.updaters);
      case ForEachPartsWithDeclaration():
        kind = 'ForEachParts';
        child('variable', ast.loopVariable);
        child('iterable', ast.iterable);
      case ForEachPartsWithIdentifier():
        kind = 'ForEachParts';
        child('variable', ast.identifier);
        child('iterable', ast.iterable);
      case DeclaredIdentifier():
        kind = 'DeclaredIdentifier';
        record['name'] = ast.name.lexeme;
        record['declaration'] = symbol(ast.declaredFragment?.element);
      case BreakStatement():
        kind = 'BreakStatement';
        child('label', ast.label);
      case ContinueStatement():
        kind = 'ContinueStatement';
        child('label', ast.label);
      case SwitchStatement():
        kind = 'SwitchStatement';
        child('condition', ast.expression);
        many('member', ast.members);
      case SwitchPatternCase()
          when ast.guardedPattern.pattern is ConstantPattern &&
              ast.guardedPattern.whenClause == null:
        kind = 'SwitchCase';
        child(
          'expression',
          (ast.guardedPattern.pattern as ConstantPattern).expression,
        );
        many('statement', ast.statements);
      case SwitchPatternCase():
        kind = 'SwitchPatternCase';
        child('guard', ast.guardedPattern);
        many('statement', ast.statements);
      case SwitchExpression():
        kind = 'SwitchExpression';
        child('expression', ast.expression);
        many('case', ast.cases);
      case SwitchExpressionCase():
        kind = 'SwitchExpressionCase';
        child('guard', ast.guardedPattern);
        child('expression', ast.expression);
      case GuardedPattern():
        kind = 'GuardedPattern';
        child('pattern', ast.pattern);
        child('when', ast.whenClause?.expression);
      case CaseClause():
        kind = 'GuardedPattern';
        child('pattern', ast.guardedPattern.pattern);
        child('when', ast.guardedPattern.whenClause?.expression);
      case SwitchCase():
        kind = 'SwitchCase';
        child('expression', ast.expression);
        many('statement', ast.statements);
      case SwitchDefault():
        kind = 'SwitchDefault';
        many('statement', ast.statements);
      case TryStatement():
        kind = 'TryStatement';
        child('body', ast.body);
        many('catch', ast.catchClauses);
        child('finally', ast.finallyBlock);
      case CatchClause():
        kind = 'CatchClause';
        child('exception', ast.exceptionParameter);
        child('stack', ast.stackTraceParameter);
        child('body', ast.body);
      case CatchClauseParameter():
        kind = 'CatchClauseParameter';
        record['name'] = ast.name.lexeme;
        record['declaration'] = symbol(ast.declaredFragment?.element);
      case ThrowExpression():
        kind = 'ThrowExpression';
        child('expression', ast.expression);
      case RethrowExpression():
        kind = 'RethrowExpression';
      case EmptyStatement():
        kind = 'EmptyStatement';
      case InstanceCreationExpression():
        kind = 'InstanceCreationExpression';
        record['target'] = symbol(ast.constructorName.element);
        record['name'] = ast.constructorName.name?.name ?? '<init>';
        child('arguments', ast.argumentList);
      case FunctionExpressionInvocation():
        kind = 'FunctionExpressionInvocation';
        child('receiver', ast.function);
        child('arguments', ast.argumentList);
      case PropertyAccess():
        kind = 'PropertyAccess';
        record['nullAware'] = ast.isNullAware;
        record['reference'] = symbol(ast.propertyName.element);
        record['name'] = ast.propertyName.name;
        child('receiver', ast.target);
      case PrefixedIdentifier():
        kind = 'PrefixedIdentifier';
        record['reference'] = symbol(ast.identifier.element);
        record['name'] = ast.identifier.name;
        child('receiver', ast.prefix);
      case ThisExpression():
        kind = 'ThisExpression';
      case SuperExpression():
        kind = 'SuperExpression';
      case ParenthesizedExpression():
        kind = 'ParenthesizedExpression';
        child('expression', ast.expression);
      case ConditionalExpression():
        kind = 'ConditionalExpression';
        child('condition', ast.condition);
        child('then', ast.thenExpression);
        child('else', ast.elseExpression);
      case PrefixExpression():
        kind = 'PrefixExpression';
        record['operator'] = ast.operator.lexeme;
        child('operand', ast.operand);
      case PostfixExpression():
        kind = 'PostfixExpression';
        record['operator'] = ast.operator.lexeme;
        child('operand', ast.operand);
      case CascadeExpression():
        kind = 'CascadeExpression';
        record['nullAware'] = ast.isNullAware;
        child('target', ast.target);
        many('section', ast.cascadeSections);
      case StringInterpolation():
        kind = 'StringInterpolation';
        many('element', ast.elements);
      case InterpolationString():
        kind = 'StringLiteral';
        record['value'] = ast.value;
      case InterpolationExpression():
        kind = 'InterpolationExpression';
        child('expression', ast.expression);
      case AdjacentStrings():
        kind = 'AdjacentStrings';
        many('element', ast.strings);
      case AwaitExpression():
        kind = 'AwaitExpression';
        child('expression', ast.expression);
      case YieldStatement():
        kind = 'YieldStatement';
        record['star'] = ast.star != null;
        child('expression', ast.expression);
      case RecordLiteral():
        kind = 'RecordLiteral';
        many('field', ast.fields);
      case PatternVariableDeclarationStatement():
        kind = 'PatternVariableDeclarationStatement';
        child('declaration', ast.declaration);
      case PatternVariableDeclaration():
        kind = 'PatternVariableDeclaration';
        child('pattern', ast.pattern);
        child('expression', ast.expression);
      case PatternAssignment():
        kind = 'PatternAssignment';
        child('pattern', ast.pattern);
        child('expression', ast.expression);
      case DeclaredVariablePattern():
        kind = 'DeclaredVariablePattern';
        record['name'] = ast.name.lexeme;
        record['declaration'] = symbol(ast.declaredFragment?.element);
        child('type', ast.type);
      case AssignedVariablePattern():
        kind = 'AssignedVariablePattern';
        record['name'] = ast.name.lexeme;
        record['reference'] = symbol(ast.element);
      case WildcardPattern():
        kind = 'WildcardPattern';
        child('type', ast.type);
      case ConstantPattern():
        kind = 'ConstantPattern';
        child('expression', ast.expression);
      case RecordPattern():
        kind = 'RecordPattern';
        many('field', ast.fields);
      case ObjectPattern():
        kind = 'ObjectPattern';
        child('type', ast.type);
        many('field', ast.fields);
      case PatternField():
        kind = 'PatternField';
        record['name'] = ast.name == null ? null : ast.effectiveName;
        record['reference'] = symbol(ast.element);
        child('pattern', ast.pattern);
      case ListPattern():
        kind = 'ListPattern';
        many('element', ast.elements);
      case MapPattern():
        kind = 'MapPattern';
        many('element', ast.elements);
      case MapPatternEntry():
        kind = 'MapPatternEntry';
        child('key', ast.key);
        child('pattern', ast.value);
      case RestPatternElement():
        kind = 'RestPatternElement';
        child('pattern', ast.pattern);
      case LogicalAndPattern():
        kind = 'LogicalAndPattern';
        child('left', ast.leftOperand);
        child('right', ast.rightOperand);
      case LogicalOrPattern():
        kind = 'LogicalOrPattern';
        child('left', ast.leftOperand);
        child('right', ast.rightOperand);
      case RelationalPattern():
        kind = 'RelationalPattern';
        record['operator'] = ast.operator.lexeme;
        child('expression', ast.operand);
      case ParenthesizedPattern():
        kind = 'ParenthesizedPattern';
        child('pattern', ast.pattern);
      case CastPattern():
        kind = 'CastPattern';
        child('pattern', ast.pattern);
        child('type', ast.type);
      case NullCheckPattern():
        kind = 'NullCheckPattern';
        child('pattern', ast.pattern);
      case NullAssertPattern():
        kind = 'NullAssertPattern';
        child('pattern', ast.pattern);
      case SpreadElement():
        kind = 'SpreadElement';
        record['nullAware'] = ast.isNullAware;
        child('expression', ast.expression);
      case IfElement():
        kind = 'IfElement';
        child('condition', ast.expression);
        child('case', ast.caseClause);
        child('then', ast.thenElement);
        child('else', ast.elseElement);
      case ForElement():
        kind = 'ForElement';
        record['await'] = ast.awaitKeyword != null;
        child('parts', ast.forLoopParts);
        child('body', ast.body);
      case ListLiteral():
        kind = 'ListLiteral';
        many('element', ast.elements);
      case SetOrMapLiteral():
        kind = 'SetOrMapLiteral';
        many('element', ast.elements);
      case MapLiteralEntry():
        kind = 'MapLiteralEntry';
        child('key', ast.key);
        child('value', ast.value);
      case IsExpression():
        kind = 'IsExpression';
        record['operator'] = ast.notOperator == null ? 'is' : 'is!';
        child('expression', ast.expression);
        child('type', ast.type);
      case AsExpression():
        kind = 'AsExpression';
        child('expression', ast.expression);
        child('type', ast.type);
      case MethodInvocation():
        kind = 'MethodInvocation';
        record['target'] = symbol(ast.methodName.element);
        record['nullAware'] = ast.isNullAware;
        child('receiver', ast.target);
        child('name', ast.methodName);
        child('arguments', ast.argumentList);
      case ArgumentList():
        kind = 'ArgumentList';
        many('argument', ast.arguments);
        record['bindings'] = ast.arguments
            .map(
              (arg) => {
                'offset': arg.offset,
                'parameter': symbol(arg.correspondingParameter),
              },
            )
            .toList();
      case NamedExpression():
        kind = 'NamedExpression';
        record['name'] = ast.name.label.name;
        child('expression', ast.expression);
      case SimpleIdentifier():
        kind = 'SimpleIdentifier';
        record['name'] = ast.name;
        record['reference'] = symbol(ast.element);
      case RecordTypeAnnotation():
        kind = 'RecordTypeAnnotation';
        record['typeId'] = typeId(ast.type);
        many('field', ast.positionalFields);
        child('named', ast.namedFields);
      case RecordTypeAnnotationNamedFields():
        kind = 'RecordTypeAnnotationNamedFields';
        many('field', ast.fields);
      case RecordTypeAnnotationField():
        kind = 'RecordTypeAnnotationField';
        record['name'] = ast.name?.lexeme;
        child('type', ast.type);
      case NamedType():
        kind = 'NamedType';
        record['typeId'] = typeId(ast.type);
        record['name'] = ast.toSource();
        child('typeArguments', ast.typeArguments);
      case TypeArgumentList():
        kind = 'TypeArgumentList';
        many('argument', ast.arguments);
      case IndexExpression():
        kind = 'IndexExpression';
        record['nullAware'] = ast.isNullAware;
        child('target', ast.target);
        child('index', ast.index);
      case AssignmentExpression():
        kind = 'AssignmentExpression';
        record['read'] = symbol(ast.readElement);
        record['write'] = symbol(ast.writeElement);
        record['operator'] = ast.operator.lexeme;
        child('left', ast.leftHandSide);
        child('right', ast.rightHandSide);
      case BinaryExpression():
        kind = 'BinaryExpression';
        record['operator'] = ast.operator.lexeme;
        child('left', ast.leftOperand);
        child('right', ast.rightOperand);
      case SimpleStringLiteral():
        kind = 'StringLiteral';
        record['value'] = ast.value;
      case IntegerLiteral():
        kind = 'IntegerLiteral';
        record['value'] = ast.value;
      case DoubleLiteral():
        kind = 'DoubleLiteral';
        record['value'] = ast.value;
      case BooleanLiteral():
        kind = 'BooleanLiteral';
        record['value'] = ast.value;
      case NullLiteral():
        kind = 'NullLiteral';
      default:
        kind = 'Unsupported';
        final syntax = ast.runtimeType.toString().replaceFirst(
          RegExp(r'Impl$'),
          '',
        );
        record['syntax'] = syntax;
        unsupported.add(syntax);
        many('child', ast.childEntities.whereType<AstNode>());
    }
    record['kind'] = kind;
    if (ast is FunctionBody) {
      record['async'] = ast.isAsynchronous;
      record['generator'] = ast.isGenerator;
    }
    if (ast is Expression) {
      record['type'] = ast.staticType?.getDisplayString();
      record['typeId'] = typeId(ast.staticType);
    }
    record['children'] = children;
    return id;
  }
}
