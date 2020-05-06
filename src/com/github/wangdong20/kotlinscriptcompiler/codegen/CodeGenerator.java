package com.github.wangdong20.kotlinscriptcompiler.codegen;

import com.github.wangdong20.kotlinscriptcompiler.parser.Program;
import com.github.wangdong20.kotlinscriptcompiler.parser.expressions.*;
import com.github.wangdong20.kotlinscriptcompiler.parser.statements.*;
import com.github.wangdong20.kotlinscriptcompiler.parser.type.BasicType;
import com.github.wangdong20.kotlinscriptcompiler.parser.type.Type;
import com.github.wangdong20.kotlinscriptcompiler.parser.type.TypeArray;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.objectweb.asm.Opcodes.*;

public class CodeGenerator {
    private static final String EMPTY_VOID = "()V";
    private final String outputClassName;
    private final String outputFunctionName;

    private final Map<String, FunctionDeclareStmt> functionTable;
    private final ClassWriter classWriter;
    private Map<Variable, VariableEntry> variables;
    private int nextIndex;
    private MethodVisitor methodVisitor;

    public CodeGenerator(final String outputClassName,
                         final String outputFunctionName) throws CodeGeneratorException {
        this.outputClassName = outputClassName;
        this.outputFunctionName = outputFunctionName;
        classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        variables = null;
        nextIndex = 0;

        functionTable = new HashMap<String, FunctionDeclareStmt>();
        classWriter.visit(V1_8, // Java 1.8 in my laptop
                ACC_PUBLIC, // public
                outputClassName, // class name
                null, // signature (null means not generic)
                "java/lang/Object", // superclass
                new String[0]); // interfaces (none)

        // ---BEGIN CONSTRUCTOR DEFINITION---
        final MethodVisitor constructor =
                classWriter.visitMethod(ACC_PUBLIC, // access modifier
                        "<init>", // method name (constructor)
                        EMPTY_VOID, // descriptor
                        null, // signature (null means not generic)
                        null); // exceptions
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0); // load "this"
        constructor.visitMethodInsn(INVOKESPECIAL,
                "java/lang/Object",
                "<init>",
                EMPTY_VOID,
                false); // super()
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        // ---END CONSTRUCTOR DEFINITION---

        // ---BEGIN MAIN DEFINITION---
        final MethodVisitor main =
                classWriter.visitMethod(ACC_PUBLIC | ACC_STATIC,
                        "main",
                        "([Ljava/lang/String;)V",
                        null,
                        null);
        main.visitCode();
        main.visitMethodInsn(INVOKESTATIC,
                outputClassName,
                outputFunctionName,
                EMPTY_VOID,
                false);
        main.visitInsn(RETURN);
        main.visitMaxs(0, 0);
        // ---END MAIN DEFINITION---

        methodVisitor = null;
    } // CodeGenerator

    private void functionStart(final FunctionDeclareStmt function) throws CodeGeneratorException {
        functionStart(function,
                Descriptor.toDescriptorString(function));
    } // functionStart

    private void functionStart(final FunctionDeclareStmt function,
                               final String descriptor)
            throws CodeGeneratorException {
        assert(variables == null);
        assert(nextIndex == 0);
        assert(methodVisitor == null);

        variables = new HashMap<Variable, VariableEntry>();
        if(function.getParameterList() != null) {
            for (Map.Entry<Exp, Type> entry : function.getParameterList().entrySet()) {
                addEntry((Variable) entry.getKey(), entry.getValue());
            }
        }

        methodVisitor = classWriter.visitMethod(ACC_PUBLIC | ACC_STATIC,
                function.getFuncName().getName(),
                descriptor,
                null,
                null);
        methodVisitor.visitCode();
    } // functionStart

    private void functionEnd() {
        assert(variables != null);
        assert(methodVisitor != null);

        methodVisitor.visitMaxs(0, 0);
        nextIndex = 0;
        variables = null;
        methodVisitor = null;
    } // functionEnd

    private VariableEntry getEntryFor(final Variable variable) throws CodeGeneratorException {
        final VariableEntry entry = variables.get(variable);
        if (entry != null) {
            return entry;
        } else if(variable instanceof ArrayWithIndexExp) {
            VariableEntry arrayEntry = variables.get(((ArrayWithIndexExp) variable).getVariableExp());
            return new VariableEntry(variable, new TypeArray((BasicType) arrayEntry.type), arrayEntry.index);
        } else {
            // should be caught by typechecker
            throw new CodeGeneratorException("no such variable declared: " + variable);
        }
    } // getEntryFor

    private VariableEntry addEntry(final Variable variable, final Type type) throws CodeGeneratorException {
        if (variables.containsKey(variable)) {
            // should be caught by typechecker
            throw new CodeGeneratorException("Variable already in scope: " + variable);
        } else if(variable instanceof ArrayWithIndexExp) {
            if(variables.containsKey(((ArrayWithIndexExp) variable).getVariableExp())) {
                throw new CodeGeneratorException("Should not add ArrayWithIndexExp into entry.");
            } else {
                throw new CodeGeneratorException("Array is not in scope: " + ((ArrayWithIndexExp) variable).getVariableExp());
            }
        } else {
            final VariableEntry entry = new VariableEntry(variable, type, nextIndex++);
            variables.put(variable, entry);
            return entry;
        }
    } // addEntry

    private void writeIntLiteral(final int value) {
        switch (value) {
            case -1:
                methodVisitor.visitInsn(ICONST_M1);
                break;
            case 0:
                methodVisitor.visitInsn(ICONST_0);
                break;
            case 1:
                methodVisitor.visitInsn(ICONST_1);
                break;
            case 2:
                methodVisitor.visitInsn(ICONST_2);
                break;
            case 3:
                methodVisitor.visitInsn(ICONST_3);
                break;
            case 4:
                methodVisitor.visitInsn(ICONST_4);
                break;
            case 5:
                methodVisitor.visitInsn(ICONST_5);
                break;
            default:
                methodVisitor.visitLdcInsn(value);
        }
    } // writeIntLiteral

    private void writeComparableOp(final ComparableOp op) throws CodeGeneratorException {
        // there is no direct instruction for these, but instead a branching
        // version IF.  Basic idea (with <):
        //   push left (assumed written already)
        //   push right (assumed written  already)
        //   iflt is_less_than
        //   push 0
        //   goto after_less_than
        // is_less_than:
        //   push 1
        // after_less_than:
        final Label conditionTrue = new Label();
        final Label afterCondition = new Label();
        switch (op) {
            case OP_LESS_THAN:
                methodVisitor.visitJumpInsn(IF_ICMPLT, conditionTrue);
                break;
            case OP_EQUAL_EQUAL:
                methodVisitor.visitJumpInsn(IF_ICMPEQ, conditionTrue);
                break;
            case OP_LESS_EQUAL:
                methodVisitor.visitJumpInsn(IF_ICMPLE, conditionTrue);
                break;
            case OP_GREATER_THAN:
                methodVisitor.visitJumpInsn(IF_ICMPGT, conditionTrue);
                break;
            case OP_GREATER_EQUAL:
                methodVisitor.visitJumpInsn(IF_ICMPGE, conditionTrue);
                break;
            case OP_NOT_EQUAL:
                methodVisitor.visitJumpInsn(IF_ICMPNE, conditionTrue);
                break;
            default:
                assert (false);
                throw new CodeGeneratorException("Unrecognized operation: " + op);
        }
        writeIntLiteral(0);
        methodVisitor.visitJumpInsn(GOTO, afterCondition);
        methodVisitor.visitLabel(conditionTrue);
        writeIntLiteral(1);
        methodVisitor.visitLabel(afterCondition);
    } // writeComparableOp

    private void writeComparableExp(ComparableExp exp) throws CodeGeneratorException {
        Exp left = exp.getLeft();
        Exp right = exp.getRight();
        writeExp(left);
        writeExp(right);
        writeComparableOp(exp.getOp());
    }

    private void writeNotExp(NotExp exp) throws CodeGeneratorException {
        Exp e = exp.getValue();
        writeExp(e);
        Label ifTrue = new Label();
        Label endIf = new Label();
        methodVisitor.visitJumpInsn(IFEQ, ifTrue);
        writeIntLiteral(0);
        methodVisitor.visitJumpInsn(GOTO, endIf);
        methodVisitor.visitLabel(ifTrue);
        writeIntLiteral(1);
        methodVisitor.visitLabel(endIf);
    }

    private VariableEntry loadVariable(Variable variable) throws CodeGeneratorException {
        final VariableEntry entry = getEntryFor(variable);
        entry.load(this, methodVisitor);
        return entry;
    }


    private void writeBiLogicalExp(BiLogicalExp exp) throws CodeGeneratorException {
        Exp left = exp.getLeft();
        Exp right = exp.getRight();
        Label ifFalse = new Label();
        Label endIf = new Label();

        switch (exp.getOp()) {
            case OP_AND:
                if(left instanceof BooleanExp) {
                    writeIntLiteral(((BooleanExp) left).getValue() ? 1 : 0);
                } else if(left instanceof ComparableExp) {
                    writeComparableExp((ComparableExp) left);
                } else if(left instanceof BiLogicalExp) {
                    writeBiLogicalExp((BiLogicalExp) left);
                } else if(left instanceof NotExp) {
                    writeNotExp((NotExp) left);
                } else if(left instanceof Variable) {
                    loadVariable((Variable) left);
                } else {
                    throw new CodeGeneratorException("Bilogical expression's left value should be boolean type");
                }

                methodVisitor.visitJumpInsn(IFEQ, ifFalse);

                if(right instanceof BooleanExp) {
                    writeIntLiteral(((BooleanExp) right).getValue() ? 1 : 0);
                } else if(right instanceof ComparableExp) {
                    writeComparableExp((ComparableExp) right);
                } else if(right instanceof BiLogicalExp) {
                    writeBiLogicalExp((BiLogicalExp) right);
                } else if(right instanceof NotExp) {
                    writeNotExp((NotExp) right);
                } else if(right instanceof Variable) {
                    loadVariable((Variable) right);
                }else {
                    throw new CodeGeneratorException("Bilogical expression's right value should be boolean type");
                }
                methodVisitor.visitJumpInsn(IFEQ, ifFalse);
                writeIntLiteral(1);
                methodVisitor.visitJumpInsn(GOTO, endIf);
                methodVisitor.visitLabel(ifFalse);
                writeIntLiteral(0);
                methodVisitor.visitLabel(endIf);
                break;
            case OP_OR:
                if(left instanceof BooleanExp) {
                    writeIntLiteral(((BooleanExp) left).getValue() ? 1 : 0);
                } else if(left instanceof ComparableExp) {
                    writeComparableExp((ComparableExp) left);
                } else if(left instanceof BiLogicalExp) {
                    writeBiLogicalExp((BiLogicalExp) left);
                } else if(left instanceof NotExp) {
                    writeNotExp((NotExp) left);
                } else if(left instanceof Variable) {
                    loadVariable((Variable) left);
                } else {
                    throw new CodeGeneratorException("Bilogical expression's left value should be boolean type");
                }

                methodVisitor.visitJumpInsn(IFNE, ifFalse);

                if(right instanceof BooleanExp) {
                    writeIntLiteral(((BooleanExp) right).getValue() ? 1 : 0);
                } else if(right instanceof ComparableExp) {
                    writeComparableExp((ComparableExp) right);
                } else if(right instanceof BiLogicalExp) {
                    writeBiLogicalExp((BiLogicalExp) right);
                } else if(right instanceof NotExp) {
                    writeNotExp((NotExp) right);
                } else if(right instanceof Variable) {
                    loadVariable((Variable) right);
                } else {
                    throw new CodeGeneratorException("Bilogical expression's right value should be boolean type");
                }
                methodVisitor.visitJumpInsn(IFNE, ifFalse);
                writeIntLiteral(0);
                methodVisitor.visitJumpInsn(GOTO, endIf);
                methodVisitor.visitLabel(ifFalse);
                writeIntLiteral(1);
                methodVisitor.visitLabel(endIf);
                break;
        }
    }

    private void writeSelfOperationExp(SelfOperationExp exp) throws CodeGeneratorException {
        int index = getEntryFor(((SelfOperationExp) exp).getVariableExp()).index;
        if(((SelfOperationExp) exp).getPreOrder()) {
            // ++i case
            if(((SelfOperationExp) exp).getOp() == SelfOp.OP_SELF_INCREASE) {
                methodVisitor.visitIincInsn(index, 1);
            } else {
                methodVisitor.visitIincInsn(index, -1);
            }
        } else {
            // i++ case
            // Did not figure out how to do i++ case
            if(((SelfOperationExp) exp).getOp() == SelfOp.OP_SELF_INCREASE) {
                methodVisitor.visitIincInsn(index, 1);
            } else {
                methodVisitor.visitIincInsn(index, -1);
            }
        }

        // load the variable after self operation
        final VariableEntry entry = getEntryFor(((SelfOperationExp) exp).getVariableExp());
        if(entry.type == BasicType.TYPE_INT) {
            entry.load(this, methodVisitor);
        } else {
            assert (false);
            throw new CodeGeneratorException("Variable in AdditiveExp should be TYPE_INT.");
        }
    }

    public void writeIfStatement(final IfStmt ifStmt) throws CodeGeneratorException {
        // if false, jump to the else branch.  If true, fall through to true branch.
        // true branch needs to jump after the false.  Looks like this:
        //
        //   condition_expression
        //   if !condition, jump to false
        //   true stuff
        //   goto after_false
        // false:
        //   false stuff
        // after_false:

        // condition is a boolean, which is represented with an integer which is either
        // 0 or 1.  IFEQ jumps if the value on top of the operand stack is 0, so this naturally
        // ends up giving us the if !condition (as odd as it looks)
        final Label falseLabel = new Label();
        final Label afterFalseLabel = new Label();
        writeExp(ifStmt.getCondition());
        methodVisitor.visitJumpInsn(IFEQ, falseLabel);
        writeStatements(ifStmt.getTrueBranch().getStmtList());
        methodVisitor.visitJumpInsn(GOTO, afterFalseLabel);
        methodVisitor.visitLabel(falseLabel);
        writeStatements(ifStmt.getFalseBranch().getStmtList());
        methodVisitor.visitLabel(afterFalseLabel);
    } // writeIfStatement

    public void writeWhileStatement(final WhileStmt whileStmt) throws CodeGeneratorException {
        // head:
        //   condition_expression
        //   if !condition, jump to after_while
        //   body
        //   goto head
        // after_while
        final Label head = new Label();
        final Label afterWhile = new Label();
        methodVisitor.visitLabel(head);
        writeExp(whileStmt.getCondition());
        methodVisitor.visitJumpInsn(IFEQ, afterWhile);
        writeStatements(whileStmt.getBlockStmt().getStmtList());
        methodVisitor.visitJumpInsn(GOTO, head);
        methodVisitor.visitLabel(afterWhile);
    } // whileWhileStatement

    public void writeStatements(final List<Stmt> stmts) throws CodeGeneratorException {
        for (final Stmt statement : stmts) {
            writeStatement(statement);
        }
    } // writeStatements

    public void writeStatement(final Stmt stmt) throws CodeGeneratorException {
        if (stmt instanceof VariableDeclareStmt) {
            // Do nothing here until initialized in AssignStmt
        } else if (stmt instanceof AssignStmt) {
            final AssignStmt asAssign = (AssignStmt)stmt;
            Type type = writeExp(asAssign.getExpression());
            final VariableEntry entry = addEntry(asAssign.getVariable(), type);
            entry.store(this, methodVisitor);
        } else if(stmt instanceof CompoundAssignStmt) {
            // support Int += first, then think about string concanation
            final CompoundAssignStmt asAssign = (CompoundAssignStmt)stmt;
            final VariableEntry entry = getEntryFor(asAssign.getVariable());
            entry.load(this, methodVisitor);
            writeExp(asAssign.getExpression());
            int opcode = 0;
            switch (((CompoundAssignStmt) stmt).getOp()) {
                case EXP_PLUS_EQUAL:
                    opcode = IADD;
                    break;
                case EXP_MINUS_EQUAL:
                    opcode = ISUB;
                    break;
                case EXP_MULTIPLY_EQUAL:
                    opcode = IMUL;
                    break;
                case EXP_DIVIDE_EQUAL:
                    opcode = IDIV;
                    break;
            }
            methodVisitor.visitInsn(opcode);
            entry.store(this, methodVisitor);
        }
        else if (stmt instanceof PrintStmt || stmt instanceof PrintlnStmt) {
            if(stmt instanceof PrintStmt) {
                writePrint((Variable) ((PrintStmt)stmt).getValue(), false);
            } else {
                writePrint((Variable) ((PrintlnStmt)stmt).getValue(), true);
            }
        } else if (stmt instanceof IfStmt) {
            writeIfStatement((IfStmt)stmt);
        } else if (stmt instanceof WhileStmt) {
            writeWhileStatement((WhileStmt)stmt);
        } else if(stmt instanceof ReturnStmt) {
            writeExp(((ReturnStmt) stmt).getReturnExp());
        } else if(stmt instanceof BlockStmt) {
            if(((BlockStmt) stmt).getStmtList() != null) {
                writeStatements(((BlockStmt) stmt).getStmtList());
            }
        } else if(stmt instanceof FunctionInstanceStmt) {
            writeFunctionInstance(((FunctionInstanceStmt) stmt).getFunctionInstanceExp());
        }
        else {
//            assert(false);
            throw new CodeGeneratorException("Unrecognized statement so far: " + stmt);
        }
    } // writeStatement

    private void writeReturnFor(final Type type) {
        if(type == BasicType.TYPE_INT ||
                type == BasicType.TYPE_BOOLEAN) {
            methodVisitor.visitInsn(IRETURN);
        } else if(type == BasicType.TYPE_UNIT) {
            methodVisitor.visitInsn(RETURN);
        } else {
            methodVisitor.visitInsn(ARETURN);
        }
    } // writeReturnFor

    private void writeFunction(final FunctionDeclareStmt function) throws CodeGeneratorException {
        functionStart(function);
        if(function.getBlockStmt() != null) {
            writeStatements(function.getBlockStmt().getStmtList());
        }
//        writeExpression(function.get);
        writeReturnFor(function.getReturnType());
        functionEnd();
    } // writeFunction

    private void writeEntryPoint(final Program program) throws CodeGeneratorException {
        functionStart(new FunctionDeclareStmt(new VariableExp(outputFunctionName), BasicType.TYPE_UNIT, null, null),
                EMPTY_VOID);
        for(Stmt s : program.getStmtList()) {
            if(!(s instanceof FunctionDeclareStmt)) {
                writeStatement(s);
            }
        }
        methodVisitor.visitInsn(RETURN);
        functionEnd();
    } // writeEntryPoint

    private Type writeFunctionInstance(final FunctionInstanceExp call) throws CodeGeneratorException {
        final FunctionDeclareStmt function = functionTable.get(call.getFuncName().getName());
        if (function == null) {
            throw new CodeGeneratorException("Call to nonexistent function: " + function.getFuncName().getName());
        }

        for (final Exp param : call.getParameterList()) {
            writeExp(param);
        }
        methodVisitor.visitMethodInsn(INVOKESTATIC,
                outputClassName,
                call.getFuncName().getName(),
                Descriptor.toDescriptorString(function),
                false);
        return function.getReturnType();
    } // writeFunctionInstance

    public Type writeExp(Exp exp) throws CodeGeneratorException {
        if(exp instanceof IntExp) {
            writeIntLiteral(((IntExp) exp).getValue());
            return BasicType.TYPE_INT;
        } else if(exp instanceof BooleanExp) {
            writeIntLiteral(((BooleanExp) exp).getValue() ? 1 : 0);
            return BasicType.TYPE_BOOLEAN;
        } else if(exp instanceof VariableExp) {
            return loadVariable((VariableExp)exp).type;
        } else if(exp instanceof ArrayWithIndexExp) {
            return loadVariable((ArrayWithIndexExp)exp).type;
        } else if(exp instanceof SelfOperationExp) {
            writeSelfOperationExp((SelfOperationExp) exp);
            return BasicType.TYPE_INT;
        } else if(exp instanceof BinaryIntExp) {
            writeAdditiveExpOrMultplicativeExp((BinaryIntExp) exp);
            return BasicType.TYPE_INT;
        } else if(exp instanceof ComparableExp) {
            writeComparableExp((ComparableExp) exp);
            return BasicType.TYPE_BOOLEAN;
        } else if(exp instanceof NotExp) {
            writeNotExp((NotExp) exp);
            return BasicType.TYPE_BOOLEAN;
        } else if(exp instanceof BiLogicalExp) {
            writeBiLogicalExp((BiLogicalExp) exp);
            return BasicType.TYPE_BOOLEAN;
        } else if(exp instanceof FunctionInstanceExp) {
            return writeFunctionInstance((FunctionInstanceExp) exp);
        }
        else {
            throw new CodeGeneratorException("Unsupported expression so far!");
        }
    }

    private void writeAdditiveExpOrMultplicativeExp(BinaryIntExp exp) throws CodeGeneratorException {
        Exp left = exp.getLeft();
        Exp right = exp.getRight();
        if(left instanceof IntExp) {
            writeIntLiteral(((IntExp) left).getValue());
        } else if(left instanceof VariableExp) {
            final VariableEntry entry = getEntryFor((VariableExp) left);
            if(entry.type == BasicType.TYPE_INT) {
                entry.load(this, methodVisitor);
            } else {
                assert (false);
                throw new CodeGeneratorException("Variable in AdditiveExp should be TYPE_INT.");
            }
        } else if(left instanceof BinaryIntExp) {
            writeAdditiveExpOrMultplicativeExp((BinaryIntExp)left);
        } else if(left instanceof SelfOperationExp) {
            writeSelfOperationExp((SelfOperationExp) left);
        } else if(left instanceof ArrayWithIndexExp) {
            final VariableEntry entry = getEntryFor((ArrayWithIndexExp) left);
            entry.load(this, methodVisitor);
        } else {
            assert (false);
            throw new CodeGeneratorException("IllTypedException should be handled in typechecker.");
        }

        // Do it again to load right Exp on stack
        if(right instanceof IntExp) {
            writeIntLiteral(((IntExp) right).getValue());
        } else if(right instanceof VariableExp) {
            final VariableEntry entry = getEntryFor((VariableExp) right);
            if(entry.type == BasicType.TYPE_INT) {
                entry.load(this, methodVisitor);
            } else {
                assert (false);
                throw new CodeGeneratorException("Variable in AdditiveExp should be TYPE_INT.");
            }
        } else if(right instanceof BinaryIntExp) {
            writeAdditiveExpOrMultplicativeExp((BinaryIntExp)right);
        } else if(right instanceof SelfOperationExp) {
            writeSelfOperationExp((SelfOperationExp) right);
        } else if(right instanceof ArrayWithIndexExp) {
            final VariableEntry entry = getEntryFor((ArrayWithIndexExp) right);
            entry.load(this, methodVisitor);
        } else {
            assert (false);
            throw new CodeGeneratorException("IllTypedException should be handled in typechecker.");
        }

        if(exp instanceof AdditiveExp) {
            AdditiveOp op = ((AdditiveExp) exp).getOp();
            if(op == AdditiveOp.EXP_PLUS) {
                methodVisitor.visitInsn(IADD);
            } else {
                methodVisitor.visitInsn(ISUB);
            }
        } else if(exp instanceof MultiplicativeExp) {
            MultiplicativeOp op = ((MultiplicativeExp) exp).getOp();
            if(op == MultiplicativeOp.OP_MULTIPLY) {
                methodVisitor.visitInsn(IMUL);
            } else if(op == MultiplicativeOp.OP_DIVIDE) {
                methodVisitor.visitInsn(IDIV);
            } else if(op == MultiplicativeOp.OP_MOD) {
                methodVisitor.visitInsn(IREM);
            }
        }
    }

    public void writePrint(final Variable variable, boolean isNewLine) throws CodeGeneratorException {
        final VariableEntry entry = getEntryFor(variable);
        final String descriptor;
        if (entry.type == BasicType.TYPE_INT) {
            descriptor = "(I)V";
        } else if (entry.type == BasicType.TYPE_BOOLEAN) {
            descriptor = "(Z)V";
        } else if(entry.type == BasicType.TYPE_STRING) {
            descriptor = "(Ljava/lang/String;)V";
        }
        else {
            assert(false);
            throw new CodeGeneratorException("Unrecognized type; " + entry.type);
        }

        methodVisitor.visitFieldInsn(GETSTATIC,
                "java/lang/System",
                "out",
                "Ljava/io/PrintStream;");
        entry.load(this, methodVisitor);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL,
                "java/io/PrintStream",
                isNewLine ? "println" : "print",
                descriptor,
                false);
    } // writePrint

    private void loadFunctionTable(final Program program) throws CodeGeneratorException {
        for (final Stmt s : program.getStmtList()) {
            if(s instanceof FunctionDeclareStmt) {
                if (functionTable.containsKey(((FunctionDeclareStmt)s).getFuncName().getName())) {
                    throw new CodeGeneratorException("Duplicate function name: " + ((FunctionDeclareStmt)s).getFuncName().getName());
                }
                functionTable.put(((FunctionDeclareStmt)s).getFuncName().getName(), (FunctionDeclareStmt)s);
            }
        }
    } // loadFunctionTable

    public void writeProgram(final Program program) throws CodeGeneratorException, IOException {
        loadFunctionTable(program);
        for (final FunctionDeclareStmt function : functionTable.values()) {
            writeFunction(function);
        }
        writeEntryPoint(program);
        classWriter.visitEnd();

        final BufferedOutputStream output =
                new BufferedOutputStream(new FileOutputStream(new File(outputClassName + ".class")));
        output.write(classWriter.toByteArray());
        output.close();
    } // writeProgram

}
