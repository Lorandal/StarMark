import React from 'react';
import {createRoot} from 'react-dom/client';
import App from './App.jsx';
import './workbench.css';
class Boundary extends React.Component {
 constructor(props){super(props);this.state={error:null};}
 static getDerivedStateFromError(error){return {error};}
 render(){return this.state.error?<main className="wb-fatal"><h1>编辑器暂时无法打开</h1><p>{this.state.error.message}</p><button onClick={()=>location.reload()}>重新载入</button><a href="/">返回主页</a></main>:this.props.children;}
}
createRoot(document.getElementById('root')).render(<Boundary><App/></Boundary>);
