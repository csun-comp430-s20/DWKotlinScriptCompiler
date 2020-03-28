package com.github.wangdong20.kotlinscriptcompiler.parser.statements;

import com.github.wangdong20.kotlinscriptcompiler.parser.expressions.Exp;
import com.github.wangdong20.kotlinscriptcompiler.parser.expressions.VariableExp;
import com.github.wangdong20.kotlinscriptcompiler.parser.type.Type;

public class AssignStmt implements Stmt {
    private final Exp expression;
    private final VariableExp variable;
    private final Type type;
    private boolean readOnly = false;

    public Exp getExpression() {
        return expression;
    }

    public VariableExp getVariable() {
        return variable;
    }

    public Type getType() {
        return type;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public AssignStmt(Exp expression, VariableExp variable, Type type, boolean readOnly) {
        this.expression = expression;
        this.variable = variable;
        this.type = type;
        this.readOnly = readOnly;
    }

    public AssignStmt(Exp expression, VariableExp variable, boolean readOnly) {
        this.expression = expression;
        this.variable = variable;
        this.type = null;
        this.readOnly = readOnly;
    }

    @Override
    public boolean equals(Object obj) {
        if(obj instanceof AssignStmt) {
            if(((AssignStmt) obj).getVariable().equals(variable) && ((AssignStmt) obj).getExpression().equals(expression) &&
                    ((AssignStmt) obj).isReadOnly() == readOnly) {
                if((((AssignStmt) obj).getType() == null && type == null) || ((AssignStmt) obj).getType().equals(type)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "AssignStmt{" +
                "expression=" + expression +
                ", variable=" + variable +
                ", type=" + type +
                ", readOnly=" + readOnly +
                '}';
    }
}
